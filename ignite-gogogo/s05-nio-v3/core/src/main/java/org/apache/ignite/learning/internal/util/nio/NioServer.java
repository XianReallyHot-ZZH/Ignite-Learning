package org.apache.ignite.learning.internal.util.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 多 worker 异步 NIO 服务器 v2(镜像 {@code GridNioServer} 的 v2 切片)。
 *
 * <p>相比 v1(单 worker)的演进:把"accept + read + write 全在一个线程"拆成
 * <ul>
 *   <li><b>1 个 accept 线程</b>:独占一个 selector,只负责 accept;</li>
 *   <li><b>N 个 {@link ClientWorker}</b>:各独占一个 selector,跑自己名下会话的 read+write。</li>
 * </ul>
 * 新连接由 {@link AtomicInteger} 轮询 Balancer 分配给某 worker(镜像 {@code offerBalanced})。
 * 每会话一条双向 {@link FilterChain}(v1 的"直连 listener"被链取代)。
 *
 * <p><b>关键不变量</b>:同一会话只属于一个 worker → 会话内无锁;并发只在跨会话之间。
 * 注册 / {@code interestOps} 必须在 owning worker 线程(非线程安全)。
 */
public final class NioServer {

    private final ServerSocketChannel serverCh;
    /** accept 线程独占的 selector,只关注 OP_ACCEPT。 */
    private final Selector acceptSelector;
    /** N 个 client worker,各独占一个 selector。 */
    private final ClientWorker[] workers;
    /** 轮询 Balancer:每来一个连接 getAndIncrement,对 worker 数取模选目标 worker。 */
    private final AtomicInteger balancer = new AtomicInteger();
    private final NioServerListener listener;

    private Thread acceptThread;
    private volatile boolean stopped;

    /**
     * 构造并绑定,但不启动线程(需另调 {@link #start()})。
     *
     * <p>(package-private:参数含包内类型 {@link Filter}。{@code middleSupplier} 每次
     * 返回 fresh 的 middle 过滤器列表,wire→app 顺序;每个会话各拿一份,保证有状态过滤器
     * 如 codec 的 Decoder 各会话独享。)</p>
     *
     * @param bind            绑定地址,端口传 0 表示由 OS 随机分配
     * @param workerCount     client worker 数(≥1)
     * @param listener        业务回调
     * @param middleSupplier  过滤链中间过滤器工厂(每会话 fresh 一份)
     */
    NioServer(InetSocketAddress bind, int workerCount, NioServerListener listener,
              Supplier<List<Filter>> middleSupplier) throws IOException {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount >= 1");
        }
        this.listener = listener;
        // 服务端通道:非阻塞 + 绑定 + 注册到 acceptSelector 只听 OP_ACCEPT
        this.serverCh = ServerSocketChannel.open();
        serverCh.configureBlocking(false);
        serverCh.bind(bind);
        this.acceptSelector = Selector.open();
        serverCh.register(acceptSelector, SelectionKey.OP_ACCEPT);

        // 每会话各建一条过滤链(Head + middle + Tail);middle 每次 fresh,保证 codec 的 Decoder 各会话独享
        Supplier<FilterChain> chainFactory = () -> FilterChain.create(middleSupplier.get(), listener);
        this.workers = new ClientWorker[workerCount];
        for (int i = 0; i < workerCount; i++) {
            this.workers[i] = new ClientWorker(i, listener, chainFactory);
        }
    }

    /** 返回实际绑定的地址(端口传 0 时拿到 OS 分配的真实端口)。 */
    public InetSocketAddress localAddress() throws IOException {
        return (InetSocketAddress) serverCh.getLocalAddress();
    }

    /**
     * 启动:先起所有 client worker(它们要接收 accept 投递的连接),再起 accept 线程。
     * 幂等(重复调用直接返回)。线程均为 daemon。
     */
    public synchronized void start() {
        if (acceptThread != null) {
            return;
        }
        for (ClientWorker w : workers) {
            w.start();
        }
        acceptThread = new Thread(this::acceptLoop, "nio-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * 停止:置停止位 → 唤醒 accept 线程并等其结束 → 逐个停 worker(各自关 selector)→ 关 server 通道与 acceptSelector。
     */
    public void stop() {
        stopped = true;
        acceptSelector.wakeup();
        try {
            if (acceptThread != null) {
                acceptThread.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (ClientWorker w : workers) {
            w.stop();
        }
        try {
            serverCh.close();
        } catch (IOException ignored) {
            // ignore
        }
        try {
            acceptSelector.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    /**
     * 发送一条消息(可在任意线程调用)。pull-based:经会话过滤链 outbound(tail→…→head),
     * 由 HeadFilter 把消息入写队列 + 唤醒 owning worker,真正 channel.write 由 worker 完成。
     */
    public void send(NioSession ses, byte[] msg) {
        ses.chain().fireOutbound(ses, msg);
    }

    /** accept 线程主循环:select 等 OP_ACCEPT → accept 新连接。 */
    private void acceptLoop() {
        try {
            while (!stopped) {
                acceptSelector.select(1000);
                Iterator<SelectionKey> it = acceptSelector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove(); // 必须手动移除
                    if (!k.isValid()) {
                        continue;
                    }
                    if (k.isAcceptable()) {
                        accept();
                    }
                }
            }
        } catch (IOException e) {
            if (!stopped) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 处理新连接:accept → 非阻塞 → 轮询选 worker → 把 channel 投递给该 worker 注册。
     *
     * <p><b>为什么不在这直接 register</b>:{@code SocketChannel.register(selector,…)} 必须对着目标 worker 的
     * selector、且在其 select 循环里才安全;accept 线程只负责"接生 + 派发",注册交给目标 worker 在自己线程做。
     */
    private void accept() throws IOException {
        SocketChannel ch = serverCh.accept();
        if (ch == null) {
            return;
        }
        ch.configureBlocking(false);
        // floorMod 保证非负(getAndIncrement 可能溢出到负,取模后修正)
        int idx = Math.floorMod(balancer.getAndIncrement(), workers.length);
        workers[idx].register(ch); // 投递给目标 worker,由它在自己线程 register + 建 NioSession
    }
}
