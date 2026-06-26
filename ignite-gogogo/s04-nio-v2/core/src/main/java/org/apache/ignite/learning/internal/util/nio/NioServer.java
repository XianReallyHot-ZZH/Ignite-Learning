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
 * 多 worker 异步 NIO 服务器 v2(镜像 {@code GridNioServer} 的 v2 切片):
 * <ul>
 *   <li>1 个 accept 线程(独占 selector,只 accept)+ N 个 {@link ClientWorker}(各一个 selector,跑 read+write)。</li>
 *   <li>{@link AtomicInteger} 轮询 Balancer 把新会话分配给某 worker(镜像 {@code offerBalanced})。</li>
 *   <li>每会话一条双向 {@link FilterChain}。</li>
 * </ul>
 * 关键不变量:同一会话只属于一个 worker(会话内无锁);注册/interestOps 必须在 owning worker 线程。
 */
public final class NioServer {

    private final ServerSocketChannel serverCh;
    private final Selector acceptSelector;
    private final ClientWorker[] workers;
    private final AtomicInteger balancer = new AtomicInteger();
    private final NioServerListener listener;

    private Thread acceptThread;
    private volatile boolean stopped;

    /** 构造(package-private:参数含包内类型 Filter)。middleSupplier 每次 returns fresh middle 过滤器(wire→app 顺序)。 */
    NioServer(InetSocketAddress bind, int workerCount, NioServerListener listener,
              Supplier<List<Filter>> middleSupplier) throws IOException {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount >= 1");
        }
        this.listener = listener;
        this.serverCh = ServerSocketChannel.open();
        serverCh.configureBlocking(false);
        serverCh.bind(bind);
        this.acceptSelector = Selector.open();
        serverCh.register(acceptSelector, SelectionKey.OP_ACCEPT);

        Supplier<FilterChain> chainFactory = () -> FilterChain.create(middleSupplier.get(), listener);
        this.workers = new ClientWorker[workerCount];
        for (int i = 0; i < workerCount; i++) {
            this.workers[i] = new ClientWorker(i, listener, chainFactory);
        }
    }

    public InetSocketAddress localAddress() throws IOException {
        return (InetSocketAddress) serverCh.getLocalAddress();
    }

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

    /** 任意线程可调用:经会话过滤链 outbound → head 入写队列 + 唤醒 owning worker(pull-based)。 */
    public void send(NioSession ses, byte[] msg) {
        ses.chain().fireOutbound(ses, msg);
    }

    private void acceptLoop() {
        try {
            while (!stopped) {
                acceptSelector.select(1000);
                Iterator<SelectionKey> it = acceptSelector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove();
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

    private void accept() throws IOException {
        SocketChannel ch = serverCh.accept();
        if (ch == null) {
            return;
        }
        ch.configureBlocking(false);
        int idx = Math.floorMod(balancer.getAndIncrement(), workers.length); // 轮询
        workers[idx].register(ch); // 交给目标 worker 在其线程注册
    }
}
