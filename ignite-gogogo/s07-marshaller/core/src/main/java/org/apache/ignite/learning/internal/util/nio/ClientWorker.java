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
 * selector worker v3(镜像 {@code AbstractNioClientWorker})。
 *
 * <p>相比 v2 的演进:
 * <ol>
 *   <li>本线程启动时标记为 <b>message-thread</b>({@link GridBackPressureControl}):本线程发送时
 *       <b>旁路发送信号量</b>,防止"worker 既处理消息又排空写队列"时自死锁;</li>
 *   <li>{@code read} 接 {@link MessageTracker}(接收背压):inbound 前后 onReceived/onProcessed,达上限暂停读;</li>
 *   <li>{@code write} 用 {@link NioSession#peekFuture()}/{@link NioSession#pollFuture()}(发送背压:poll 释放信号量);</li>
 *   <li>支持 {@link #submit(Runnable)}:pauseReads/resumeReads/triggerReconnect 借它在 owner 线程翻 interestOps。</li>
 * </ol>
 */
final class ClientWorker {

    private final int id;
    /** 本 worker 独占的 selector(非线程安全,只在本线程用)。 */
    private final Selector selector;
    /** 仅用其 onConnected/onDisconnected(消息经链→TailFilter→onMessage),故通配类型即可。 */
    private final NioServerListener<?> listener;
    private final Supplier<FilterChain> chainFactory;
    /** worker 私有读缓冲(只在 worker 线程用,无需同步)。 */
    private final ByteBuffer readBuf = ByteBuffer.allocate(8 * 1024);
    /** 跨线程投递任务(注册 / pause/resume / triggerReconnect):生产者任意线程,消费者本 worker 线程。 */
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private volatile boolean stopped;

    ClientWorker(int id, NioServerListener<?> listener, Supplier<FilterChain> chainFactory) throws IOException {
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

    /** 唤醒正在 select 阻塞的本 worker。 */
    void wakeup() {
        selector.wakeup();
    }

    /** 在本 worker 线程异步执行(interestOps 翻转等必须在 owner 线程做的操作)。 */
    void submit(Runnable r) {
        tasks.offer(r);
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

    /** accept 线程调用:把新 channel 交给本 worker 在其线程注册(注册必须在 owner 线程)。 */
    void register(SocketChannel ch) {
        tasks.offer(() -> {
            try {
                SelectionKey key = ch.register(selector, SelectionKey.OP_READ);
                NioSession ses = new NioSession(ch, key, chainFactory.get(), this);
                key.attach(ses);
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
     * worker 主循环(同 v2 的三步走:drain 任务 → arm OP_WRITE → 分发事件),
     * 唯一区别:循环开始先把本线程标记为 message-thread(发送旁路信号量防死锁)。
     */
    private void run() {
        GridBackPressureControl.enterMessageThread(); // 本线程处理消息 → 发送旁路信号量防死锁
        try {
            while (!stopped) {
                selector.select(1000);

                // 1) drain 投递任务(注册 / pause/resume / triggerReconnect)
                Runnable t;
                while ((t = tasks.poll()) != null) {
                    t.run();
                }
                if (stopped) {
                    break;
                }
                // 2) 为有待发数据的会话 arm OP_WRITE(interestOps 必须在 owner 线程)
                for (SelectionKey k : selector.keys()) {
                    if (k.attachment() instanceof NioSession) {
                        NioSession ses = (NioSession) k.attachment();
                        if (ses.hasPendingWrites()) {
                            k.interestOps(k.interestOps() | SelectionKey.OP_WRITE);
                        }
                    }
                }
                // 3) 分发就绪事件
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove();
                    if (!k.isValid()) {
                        continue;
                    }
                    try {
                        if (k.attachment() instanceof NioSession) {
                            NioSession ses = (NioSession) k.attachment();
                            if (k.isReadable()) {
                                read(ses);
                            }
                            if (k.isValid() && k.isWritable()) {
                                write(ses, k);
                            }
                        }
                    } catch (IOException e) {
                        closeSession(k, (NioSession) k.attachment());
                    }
                }
            }
        } catch (IOException e) {
            if (!stopped) {
                e.printStackTrace();
            }
        } finally {
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
     * 读取:channel.read → flip → 接收背压(inbound 前 onReceived、后 onProcessed)→ 经链 inbound。
     * tracker 为 null(默认)时退化为 v2 行为。
     */
    private void read(NioSession ses) throws IOException {
        readBuf.clear();
        int n = ses.channel().read(readBuf);
        if (n == -1) {
            closeSession(ses.key(), ses);
            return;
        }
        if (n == 0) {
            return;
        }
        readBuf.flip();
        MessageTracker tracker = ses.tracker();
        if (tracker != null) {
            tracker.onReceived(); // 在途 +1,达上限则 pauseReads(关 OP_READ,让对端别再灌)
        }
        ses.chain().fireInbound(ses, readBuf); // 同步 listener:onMessage 在此返回前完成
        if (tracker != null) {
            tracker.onProcessed(); // 在途 -1,低于上限则 resumeReads
        }
    }

    /**
     * 写出:peek 队首 → channel.write;socket 满(剩余)则留队首(OP_WRITE 保持 armed,下轮续写);
     * 完整写出则 poll(弹出 + 释放发送信号量);队列排空则 disarm OP_WRITE。
     */
    private void write(NioSession ses, SelectionKey key) throws IOException {
        while (true) {
            ByteBuffer buf = ses.peekFuture();
            if (buf == null) {
                break;
            }
            ses.channel().write(buf);
            if (buf.hasRemaining()) {
                return; // socket 满,留队首,OP_WRITE 保持 armed
            }
            ses.pollFuture(); // 完整写出:弹出(+ 释放发送信号量)
        }
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE); // 排空:disarm,避免空转
    }

    /** 关闭单个会话:置关闭位 → cancel key → 关 channel → 回调 onDisconnected。 */
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
