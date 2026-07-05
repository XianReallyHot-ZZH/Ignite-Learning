package org.apache.ignite.learning.internal.util.nio;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * 会话状态 v3(镜像 {@code GridNioSession} / {@code GridSelectorNioSessionImpl})。
 *
 * <p>相比 v2 的演进:
 * <ul>
 *   <li>写队列改 {@link BoundedWriteQueue}(发送背压:有界 + 信号量);</li>
 *   <li>新增可选 {@link RecoveryDescriptor}(断线重连重发)与 {@link MessageTracker}(接收背压);</li>
 *   <li>{@code pauseReads/resumeReads} 交 owning worker 翻 {@code OP_READ}。</li>
 * </ul>
 * <p><b>默认</b>:无界写队列、无 recovery、无 tracker → 行为同 v2(echo 等不受影响);三者均按需开启。</p>
 */
public final class NioSession {

    private final SocketChannel ch;
    private final SelectionKey key;
    private final FilterChain chain;
    private final ClientWorker myWorker;
    /** 有界写队列(发送背压);默认 limit=0=无界(无信号量)。 */
    private final BoundedWriteQueue writeQueue;

    private RecoveryDescriptor recoveryDesc; // 可选:断线重连重发
    private MessageTracker tracker;          // 可选:接收背压
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
    // offer/peek/poll 三件套封装 BoundedWriteQueue:offer 满则阻塞(信号量),poll 释放。
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

    /** recovery 未确认队列溢出 → 关闭 channel,触发消费者重连(镜像 Ignite 的溢出重连自愈)。 */
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

    /** 暂停读:交 owning worker 在其线程清 OP_READ(interestOps 非线程安全,必须 owner 线程)。 */
    void pauseReads() {
        myWorker.submit(() -> {
            try {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
            } catch (Exception ignored) {
                // ignore
            }
        });
    }

    /** 恢复读:交 owning worker 在其线程置 OP_READ。 */
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
