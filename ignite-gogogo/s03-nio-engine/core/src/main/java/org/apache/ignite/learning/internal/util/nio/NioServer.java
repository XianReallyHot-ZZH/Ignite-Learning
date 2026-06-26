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

    private final Selector selector;
    private final ServerSocketChannel serverCh;
    private final NioServerListener listener;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** worker 线程私有的读缓冲(只在 worker 线程使用,无需同步)。 */
    private final ByteBuffer readBuf = ByteBuffer.allocate(8 * 1024);

    private Thread worker;

    public NioServer(InetSocketAddress bind, NioServerListener listener) throws IOException {
        this.listener = listener;
        this.selector = Selector.open();
        this.serverCh = ServerSocketChannel.open();
        serverCh.configureBlocking(false);
        serverCh.bind(bind);
        serverCh.register(selector, SelectionKey.OP_ACCEPT);
    }

    public InetSocketAddress localAddress() throws IOException {
        return (InetSocketAddress) serverCh.getLocalAddress();
    }

    public synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(this::run, "nio-server-worker");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        selector.wakeup();
        try {
            if (worker != null) {
                worker.join(2000);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
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

    private void run() {
        try {
            while (!stopped.get()) {
                selector.select(1000);
                if (stopped.get()) {
                    break;
                }
                // 在 worker 线程为有待发数据的会话 arm OP_WRITE(SelectionKey.interestOps 非线程安全,必须在 owner 线程做)
                for (SelectionKey k : selector.keys()) {
                    if (k.attachment() instanceof NioSession) {
                        NioSession ses = (NioSession) k.attachment();
                        if (!ses.writeQueue().isEmpty()) {
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
                        if (k.isAcceptable()) {
                            accept();
                        } else if (k.attachment() instanceof NioSession) {
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
            if (!stopped.get()) {
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
        SelectionKey key = ch.register(selector, SelectionKey.OP_READ);
        NioSession ses = new NioSession(ch, key);
        key.attach(ses);
        listener.onConnected(ses);
    }

    private void read(NioSession ses) throws IOException {
        SocketChannel ch = ses.channel();
        readBuf.clear();
        int n = ch.read(readBuf);
        if (n == -1) {
            closeSession(ses.key(), ses);
            return;
        }
        if (n == 0) {
            return;
        }
        readBuf.flip();
        for (byte[] frame : ses.decoder().decode(readBuf)) {
            listener.onMessage(ses, frame);
        }
    }

    private void write(NioSession ses, SelectionKey key) throws IOException {
        SocketChannel ch = ses.channel();
        ConcurrentLinkedQueue<ByteBuffer> q = ses.writeQueue();
        while (true) {
            ByteBuffer buf = q.peek();
            if (buf == null) {
                break;
            }
            ch.write(buf);
            if (buf.hasRemaining()) {
                // socket 缓冲满,保留在队首,保持 OP_WRITE 已 arm,下个周期继续(OS 背压)
                return;
            }
            q.poll();
        }
        // 队列排空:disarm OP_WRITE,避免空转
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
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
        }
        if (ses != null) {
            try {
                ses.channel().close();
            } catch (IOException ignored) {
            }
            listener.onDisconnected(ses);
        }
    }
}
