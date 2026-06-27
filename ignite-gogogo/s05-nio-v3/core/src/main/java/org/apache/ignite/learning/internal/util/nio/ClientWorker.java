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
 * 相比 v2:① 本线程标记为 message-thread(发送旁路信号量防死锁);② read 接 {@link MessageTracker}(接收背压);
 * ③ write 用 {@link NioSession#peekFuture()}/{@link NioSession#pollFuture()}(发送背压);④ 支持 {@link #submit(Runnable)}
 * 供 pauseReads/resumeReads/triggerReconnect 在本线程翻 interestOps。
 */
final class ClientWorker {

    private final int id;
    private final Selector selector;
    private final NioServerListener listener;
    private final Supplier<FilterChain> chainFactory;
    private final ByteBuffer readBuf = ByteBuffer.allocate(8 * 1024);
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

    void wakeup() {
        selector.wakeup();
    }

    /** 在本 worker 线程异步执行(用于 interestOps 翻转等必须在 owner 线程做的操作)。 */
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

    /** accept 线程调用:把新 channel 交给本 worker 在其线程注册。 */
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

    private void run() {
        GridBackPressureControl.enterMessageThread(); // 本线程处理消息 → 发送旁路信号量防死锁
        try {
            while (!stopped) {
                selector.select(1000);

                Runnable t;
                while ((t = tasks.poll()) != null) {
                    t.run();
                }
                if (stopped) {
                    break;
                }
                for (SelectionKey k : selector.keys()) {
                    if (k.attachment() instanceof NioSession) {
                        NioSession ses = (NioSession) k.attachment();
                        if (ses.hasPendingWrites()) {
                            k.interestOps(k.interestOps() | SelectionKey.OP_WRITE);
                        }
                    }
                }
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
            tracker.onReceived();
        }
        ses.chain().fireInbound(ses, readBuf);
        if (tracker != null) {
            tracker.onProcessed();
        }
    }

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
            ses.pollFuture(); // 完整写出:弹出(+ 释放信号量)
        }
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE); // 排空:disarm
    }

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
