package org.apache.ignite.learning.internal.util.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * 一个 selector worker(镜像 {@code AbstractNioClientWorker}):独占一个 {@link Selector},
 * 处理其名下所有会话的 read+write。
 *
 * <p><b>关键不变量</b>:同一会话从注册起绑定唯一 worker,它的 read/write 全在本 worker 线程串行 →
 * <b>会话内无锁</b>;并发只在跨会话之间。正因如此,channel 的注册、{@code interestOps} 的修改、
 * 写队列的读写都不需要同步——都发生在本 worker 线程上(写队列用并发集合只是为了跨线程入队)。</p>
 */
final class ClientWorker {

    private final int id;
    /** 本 worker 独占的 selector(非线程安全,只在本线程用)。 */
    private final Selector selector;
    private final NioServerListener listener;
    /** 每会话各建一条过滤链的工厂(每会话 fresh,保证 codec 的 Decoder 独享)。 */
    private final Supplier<FilterChain> chainFactory;
    /** worker 私有的读缓冲(只在 worker 线程用,无需同步)。 */
    private final ByteBuffer readBuf = ByteBuffer.allocate(8 * 1024);
    /** 跨线程投递的任务队列(注册、pause/resume 等):生产者任意线程,消费者本 worker 线程。 */
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private volatile boolean stopped;

    ClientWorker(int id, NioServerListener listener, Supplier<FilterChain> chainFactory) throws IOException {
        this.id = id;
        this.listener = listener;
        this.chainFactory = chainFactory;
        this.selector = Selector.open();
        this.thread = new Thread(this::run, "nio-worker-" + id);
        this.thread.setDaemon(true);
    }

    int id() {
        return id;
    }

    void start() {
        thread.start();
    }

    /** 唤醒正在 select 阻塞的本 worker(让它在下轮循环处理新投递的任务/写队列)。 */
    void wakeup() {
        selector.wakeup();
    }

    void stop() {
        stopped = true;
        selector.wakeup();
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * accept 线程调用:把新 channel 交给本 worker。注册必须在 owner 线程做(见 {@link #run} 的 drain),
     * 故这里只把"注册动作"包装成 Runnable 投递到 {@link #tasks},再 wakeup 本 worker。
     */
    void register(SocketChannel ch) {
        tasks.offer(() -> {
            try {
                // 在本 worker 线程注册到自己的 selector,初始只关注 OP_READ
                SelectionKey key = ch.register(selector, SelectionKey.OP_READ);
                // 建会话:绑定 channel/key/专属过滤链/本 worker
                NioSession ses = new NioSession(ch, key, chainFactory.get(), this);
                key.attach(ses); // 后续事件通过 key.attachment() 取回 session
                listener.onConnected(ses);
            } catch (IOException e) {
                try {
                    ch.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        });
        selector.wakeup();
    }

    /**
     * worker 主循环:每轮三步走。
     * <ol>
     *   <li>{@code select(1000)} 阻塞等 IO 事件(最多 1s,兼顾 wakeup 响应与兜底);</li>
     *   <li>drain 投递任务(注册等必须在 owner 线程做的操作);</li>
     *   <li>为有待发数据的会话 arm OP_WRITE(pull-based 写的核心);</li>
     *   <li>分发就绪事件(accept 在 NioServer,这里只 read/write)。</li>
     * </ol>
     */
    private void run() {
        try {
            while (!stopped) {
                selector.select(1000);

                // 1) drain 投递任务:注册等必须在 owner 线程做的操作
                Runnable t;
                while ((t = tasks.poll()) != null) {
                    t.run();
                }
                if (stopped) {
                    break;
                }
                // 2) 为有待发数据的会话 arm OP_WRITE。
                //    interestOps 非线程安全,必须在 owner(本)线程调用;
                //    业务线程的 send 只入队 + wakeup,这里才真正 arm。
                for (SelectionKey k : selector.keys()) {
                    if (k.attachment() instanceof NioSession) {
                        NioSession ses = (NioSession) k.attachment();
                        if (!ses.writeQueue().isEmpty()) {
                            k.interestOps(k.interestOps() | SelectionKey.OP_WRITE);
                        }
                    }
                }
                // 3) 分发就绪事件
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove(); // 必须手动移除,否则下轮重复拿到
                    if (!k.isValid()) {
                        continue;
                    }
                    try {
                        if (k.attachment() instanceof NioSession) {
                            NioSession ses = (NioSession) k.attachment();
                            if (k.isReadable()) {
                                read(ses);
                            }
                            // 重新检查 isValid:read 可能触发 closeSession 导致 key 失效
                            if (k.isValid() && k.isWritable()) {
                                write(ses, k);
                            }
                        }
                    } catch (IOException e) {
                        // 单连接异常不影响其他连接,只关当前会话
                        closeSession(k, (NioSession) k.attachment());
                    }
                }
            }
        } catch (IOException e) {
            if (!stopped) {
                e.printStackTrace();
            }
        } finally {
            // 退出前关闭所有通道与 selector
            for (SelectionKey k : selector.keys()) {
                try {
                    k.channel().close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
            try {
                selector.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    /**
     * 读取:channel.read → flip → 经会话过滤链 inbound(head→codec→…→tail→listener)。
     * n == -1 表示对端正常关闭(FIN);n == 0 表示本次无数据(非阻塞特有)。
     */
    private void read(NioSession ses) throws IOException {
        readBuf.clear(); // 复用读缓冲:先 clear 回写模式
        int n = ses.channel().read(readBuf);
        if (n == -1) {
            closeSession(ses.key(), ses);
            return;
        }
        if (n == 0) {
            return;
        }
        readBuf.flip(); // 写→读,交给链
        ses.chain().fireInbound(ses, readBuf); // ByteBuffer 进链:head→codec→…→tail→listener
    }

    /**
     * 写出:把写队列里的 ByteBuffer 逐个 channel.write;socket 缓冲满则留队首(保持 OP_WRITE armed,下轮续写);
     * 队列排空则 disarm OP_WRITE(避免空转烧 CPU)。pull-based:真正的 socket 写只发生在本 worker 线程。
     */
    private void write(NioSession ses, SelectionKey key) throws IOException {
        ConcurrentLinkedQueue<ByteBuffer> q = ses.writeQueue();
        while (true) {
            ByteBuffer buf = q.peek();
            if (buf == null) {
                break;
            }
            ses.channel().write(buf);
            if (buf.hasRemaining()) {
                return; // socket 满,留队首,OP_WRITE 保持 armed,下轮 select 续写
            }
            q.poll(); // 完整写出,弹出
        }
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE); // 排空:disarm,避免空转
    }

    /** 关闭单个会话:置关闭位 → cancel key → 关 channel → 回调 onDisconnected。单连接异常时清理用。 */
    private void closeSession(SelectionKey key, NioSession ses) {
        if (ses != null) {
            ses.markClosed();
        }
        try {
            if (key != null) {
                key.cancel();
            }
        } catch (Exception ignored) {
            // ignore
        }
        if (ses != null) {
            try {
                ses.channel().close();
            } catch (IOException ignored) {
                // ignore
            }
            listener.onDisconnected(ses);
        }
    }
}
