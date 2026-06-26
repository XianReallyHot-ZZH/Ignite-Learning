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
 * 处理其名下所有会话的 read+write。同一会话只属于一个 worker → 会话内无锁。
 * <p>关键:channel 的注册、{@code interestOps} 的修改都在本 worker 线程(线程安全)。
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

    void stop() {
        stopped = true;
        selector.wakeup();
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** accept 线程调用:把新 channel 交给本 worker,由本线程注册(interestOps/注册必须在 owner 线程)。 */
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
        try {
            while (!stopped) {
                selector.select(1000);

                // 1) drain 投递任务(注册等)
                Runnable t;
                while ((t = tasks.poll()) != null) {
                    t.run();
                }
                if (stopped) {
                    break;
                }
                // 2) 为有待发数据的会话 arm OP_WRITE(在 owner 线程,线程安全)
                for (SelectionKey k : selector.keys()) {
                    if (k.attachment() instanceof NioSession) {
                        NioSession ses = (NioSession) k.attachment();
                        if (!ses.writeQueue().isEmpty()) {
                            k.interestOps(k.interestOps() | SelectionKey.OP_WRITE);
                        }
                    }
                }
                // 3) 处理就绪 key
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
        ses.chain().fireInbound(ses, readBuf); // ByteBuffer 进链,head→codec→…→tail→listener
    }

    private void write(NioSession ses, SelectionKey key) throws IOException {
        ConcurrentLinkedQueue<ByteBuffer> q = ses.writeQueue();
        while (true) {
            ByteBuffer buf = q.peek();
            if (buf == null) {
                break;
            }
            ses.channel().write(buf);
            if (buf.hasRemaining()) {
                return; // socket 满,留队首,OP_WRITE 保持 armed
            }
            q.poll();
        }
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE); // 排空:disarm,避免空转
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
