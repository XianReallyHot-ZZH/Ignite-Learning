package org.apache.ignite.learning.internal.util.nio;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * 会话状态 v3(镜像 {@code GridNioSession} / {@code GridSelectorNioSessionImpl})。
 * 相比 v2:写队列改 {@link BoundedWriteQueue}(发送背压);新增可选 {@link RecoveryDescriptor}
 * 与 {@link MessageTracker}(接收背压);{@code pauseReads/resumeReads} 交 owning worker 翻 OP_READ。
 * 默认:无界写队列、无 recovery、无 tracker → 行为同 v2(echo 等不受影响)。
 */
public final class NioSession {

    private final SocketChannel ch;
    private final SelectionKey key;
    private final FilterChain chain;
    private final ClientWorker myWorker;
    private final BoundedWriteQueue writeQueue;

    private RecoveryDescriptor recoveryDesc; // 可选
    private MessageTracker tracker;          // 可选
    private final Object[] meta = new Object[16];
    private volatile boolean closed;

    public NioSession(SocketChannel ch, SelectionKey key, FilterChain chain, ClientWorker myWorker) {
        this(ch, key, chain, myWorker, new BoundedWriteQueue(0)); // 默认无界
    }

    public NioSession(SocketChannel ch, SelectionKey key, FilterChain chain, ClientWorker myWorker,
                      BoundedWriteQueue writeQueue) {
        this.ch = ch;
        this.key = key;
        this.chain = chain;
        this.myWorker = myWorker;
        this.writeQueue = writeQueue;
    }

    public SocketChannel channel() {
        return ch;
    }

    public SelectionKey key() {
        return key;
    }

    public int workerId() {
        return myWorker.id();
    }

    public boolean isClosed() {
        return closed;
    }

    public InetSocketAddress remoteAddress() {
        try {
            return (InetSocketAddress) ch.getRemoteAddress();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 写队列(发送背压)----
    void offerFuture(ByteBuffer buf) {
        writeQueue.offer(buf);
    }

    ByteBuffer peekFuture() {
        return writeQueue.peek();
    }

    ByteBuffer pollFuture() {
        return writeQueue.poll();
    }

    boolean hasPendingWrites() {
        return !writeQueue.isEmpty();
    }

    // ---- recovery(可选)----
    public void recoveryDescriptor(RecoveryDescriptor rd) {
        this.recoveryDesc = rd;
    }

    RecoveryDescriptor recoveryDescriptor() {
        return recoveryDesc;
    }

    /** recovery 队列溢出 → 关闭 channel,触发消费者重连(镜像 Ignite 的溢出重连)。 */
    void triggerReconnect() {
        myWorker.submit(() -> {
            try {
                ch.close();
            } catch (Exception ignored) {
                // ignore
            }
        });
    }

    // ---- 接收背压(可选)----
    public void tracker(MessageTracker t) {
        this.tracker = t;
    }

    MessageTracker tracker() {
        return tracker;
    }

    /** 暂停/恢复读 → 交 owning worker 在其线程翻 OP_READ(interestOps 非线程安全)。 */
    void pauseReads() {
        myWorker.submit(() -> {
            try {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
            } catch (Exception ignored) {
                // ignore
            }
        });
    }

    void resumeReads() {
        myWorker.submit(() -> {
            try {
                key.interestOps(key.interestOps() | SelectionKey.OP_READ);
            } catch (Exception ignored) {
                // ignore
            }
        });
    }

    FilterChain chain() {
        return chain;
    }

    ClientWorker myWorker() {
        return myWorker;
    }

    void markClosed() {
        closed = true;
    }
}
