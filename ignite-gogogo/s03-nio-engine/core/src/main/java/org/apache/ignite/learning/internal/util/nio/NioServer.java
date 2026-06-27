package org.apache.ignite.learning.internal.util.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单 selector worker 的异步 NIO 服务器(镜像 {@code GridNioServer} 的 v1 切片)。
 * <p>关键设计(详见 S03 教学文档):
 * <ul>
 *   <li>单 worker 线程独占一个 Selector,跑 accept + read + write。</li>
 *   <li>pull-based 写:{@link #send(NioSession, byte[])} 只入队 + wakeup,真正 {@code channel.write} 由
 *       OP_WRITE 就绪驱动,保证 channel 只被 worker 线程访问(线程安全)。</li>
 *   <li>OP_WRITE 按需开关:有数据待写才 arm,写空即 disarm,避免空转烧 CPU。</li>
 * </ul>
 */
public final class NioServer {

    /** IO 多路复用器,由 worker 线程独占(非线程安全)。 */
    private final Selector selector;
    /** 服务端监听通道,非阻塞模式,注册 OP_ACCEPT。 */
    private final ServerSocketChannel serverCh;
    /** 业务回调:连接/断开/消息到达。 */
    private final NioServerListener listener;
    /** 停止标志,用 CAS 保证 stop 只执行一次。 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** worker 线程私有的读缓冲(只在 worker 线程使用,无需同步)。 */
    private final ByteBuffer readBuf = ByteBuffer.allocate(8 * 1024);

    /** 独占 selector 的单 worker 线程,daemon 模式(JVM 退出时自动结束)。 */
    private Thread worker;

    /**
     * 构造并绑定服务器,但尚不启动 worker 线程(需另调 {@link #start()})。
     *
     * @param bind     绑定地址,端口传 0 表示由 OS 随机分配空闲端口
     * @param listener 业务回调
     */
    public NioServer(InetSocketAddress bind, NioServerListener listener) throws IOException {
        this.listener = listener;
        this.selector = Selector.open();
        this.serverCh = ServerSocketChannel.open();
        serverCh.configureBlocking(false);              // NIO 必须非阻塞
        serverCh.bind(bind);
        serverCh.register(selector, SelectionKey.OP_ACCEPT); // 只监听 ACCEPT
    }

    /** 返回实际绑定的地址(端口传 0 时,这里拿到 OS 分配的真实端口)。 */
    public InetSocketAddress localAddress() throws IOException {
        return (InetSocketAddress) serverCh.getLocalAddress();
    }

    /**
     * 启动 worker 线程(幂等,重复调用直接返回)。
     * 线程为 daemon,不会阻止 JVM 退出。
     */
    public synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(this::run, "nio-server-worker");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 停止服务器:置停止位 → 唤醒 worker → 等待结束 → 关闭所有通道和 selector。
     * 用 CAS 保证只执行一次关闭逻辑。
     */
    public void stop() {
        // CAS 保证只关闭一次;已经停过则直接返回
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        // 唤醒正在 select 阻塞的 worker,让它检查 stopped 标志并退出循环
        selector.wakeup();
        try {
            // 等待 worker 线程结束(最多 2s,防止死锁)
            if (worker != null) {
                worker.join(2000);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // 清理:关闭所有注册的通道(含 server socket 和已连接的 client socket)
        for (SelectionKey k : selector.keys()) {
            try {
                k.channel().close();
            } catch (IOException ignored) {
            }
        }
        try {
            selector.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 发送一条消息(可在任意线程调用)。pull-based:只入队 + 唤醒 worker,真正写由 worker 完成。
     */
    public void send(NioSession ses, byte[] msg) {
        ses.writeQueue().offer(FrameCodec.encode(msg));
        selector.wakeup();
    }

    /**
     * worker 线程主循环:select → arm OP_WRITE → 分发 IO 事件。
     *
     * <p>每轮循环三步走:
     * <ol>
     *   <li>{@code select(1000)}:阻塞等 IO 事件,最多 1s 超时(兼顾 wakeup 响应和兜底)</li>
     *   <li>遍历所有 key,为写队列非空的 session arm OP_WRITE(pull-based 写的核心)</li>
     *   <li>遍历 selectedKeys,分发 accept / read / write 事件</li>
     * </ol>
     */
    private void run() {
        try {
            while (!stopped.get()) {
                // 阻塞等待 IO 事件,最多 1s;被 wakeup 或超时后返回
                selector.select(1000);
                if (stopped.get()) {
                    break;
                }
                // ===== arm OP_WRITE:遍历所有 session,写队列非空就关注 OP_WRITE =====
                // interestOps 非线程安全,必须在 worker(owner)线程调用
                // 业务线程调 send 只入队 + wakeup,这里才真正 arm
                for (SelectionKey k : selector.keys()) {
                    if (k.attachment() instanceof NioSession) {
                        NioSession ses = (NioSession) k.attachment();
                        if (!ses.writeQueue().isEmpty()) {
                            k.interestOps(k.interestOps() | SelectionKey.OP_WRITE);
                        }
                    }
                }
                // ===== 分发就绪事件 =====
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove(); // 必须手动移除,否则下轮还会重复拿到
                    if (!k.isValid()) {
                        continue;
                    }
                    try {
                        if (k.isAcceptable()) {
                            accept();
                        } else if (k.attachment() instanceof NioSession) {
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
            // selector 级别的异常才到这里;正常停止时不打印
            if (!stopped.get()) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 处理新连接:accept 拿到 SocketChannel → 设非阻塞 → 注册 OP_READ → 创建 NioSession → 回调 onConnected。
     */
    private void accept() throws IOException {
        SocketChannel ch = serverCh.accept();
        if (ch == null) {
            return; // 理论上 OP_ACCEPT 就绪时不会为 null,防御性检查
        }
        ch.configureBlocking(false); // 新连接也必须非阻塞
        SelectionKey key = ch.register(selector, SelectionKey.OP_READ); // 初始只关注读
        NioSession ses = new NioSession(ch, key);
        key.attach(ses); // 把 session 绑到 key 上,后续事件通过 attachment 取回
        listener.onConnected(ses);
    }

    /**
     * 读取数据:channel.read → 帧解码(粘包/半包) → 对每条完整消息回调 onMessage。
     *
     * <p>n == -1 表示对端正常关闭(FIN);n == 0 表示本次无数据(非阻塞特有)。</p>
     */
    private void read(NioSession ses) throws IOException {
        SocketChannel ch = ses.channel();
        readBuf.clear(); // 复用读缓冲:先 clear 回到写模式
        int n = ch.read(readBuf);
        if (n == -1) {
            // 对端关闭连接,清理会话
            closeSession(ses.key(), ses);
            return;
        }
        if (n == 0) {
            return; // 非阻塞 read,本次没有数据可读
        }
        readBuf.flip(); // 写→读模式,准备交给解码器
        // 每个连接独享一个 Decoder(非线程安全),decode 返回 0~N 条完整帧
        for (byte[] frame : ses.decoder().decode(readBuf)) {
            listener.onMessage(ses, frame);
        }
    }

    /**
     * 写出队列中待发数据:循环 peek + channel.write,直到队列空或 socket 缓冲满。
     *
     * <p>背压处理:socket 发送缓冲区满时 channel.write 写不完整(buf.hasRemaining),
     * 保留在队首不 poll,OP_WRITE 保持 arm,下轮 select 会再次触发 write 继续写。</p>
     *
     * <p>队列排空后必须 disarm OP_WRITE,否则 select 会持续返回 OP_WRITE 就绪(CPU 空转)。</p>
     */
    private void write(NioSession ses, SelectionKey key) throws IOException {
        SocketChannel ch = ses.channel();
        ConcurrentLinkedQueue<ByteBuffer> q = ses.writeQueue();
        while (true) {
            ByteBuffer buf = q.peek(); // 看队首但不移除(写成功才 poll)
            if (buf == null) {
                break; // 队列空,写完
            }
            ch.write(buf); // 非阻塞写,返回后 buf 可能还有剩余
            if (buf.hasRemaining()) {
                // socket 缓冲满:保留在队首,保持 OP_WRITE 已 arm,下个周期继续(OS 背压)
                return;
            }
            q.poll(); // 当前 buf 写完,移出队列
        }
        // 队列排空:disarm OP_WRITE,避免空转
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }

    /**
     * 关闭会话:标记关闭 → cancel key(从 selector 注销)→ 关闭 channel → 回调 onDisconnected。
     * 每步 try-catch 独立,保证一步失败不影响后续清理。
     */
    private void closeSession(SelectionKey key, NioSession ses) {
        if (ses != null) {
            ses.markClosed();
        }
        try {
            if (key != null) {
                key.cancel(); // 从 selector 取消注册
            }
        } catch (Exception ignored) {
        }
        if (ses != null) {
            try {
                ses.channel().close(); // 关闭底层 socket
            } catch (IOException ignored) {
            }
            listener.onDisconnected(ses); // 通知业务层
        }
    }
}
