package org.apache.ignite.learning.internal.util.nio;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 一个连接的会话状态(镜像 {@code GridNioSession} / {@code GridSelectorNioSessionImpl})。
 * 持有写队列、帧解码器、meta 数组(而非 Map,零分配,对齐 Ignite 习惯)。
 * 同一会话的所有 IO 由其 owning worker 线程串行处理 → 会话内无锁。
 */
public final class NioSession {

    private final SocketChannel ch;
    private final SelectionKey key;

    /** 发送队列(pull-based:send 只入队,真正写由 worker 在 OP_WRITE 就绪时做)。 */
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();

    private final FrameCodec.Decoder decoder = new FrameCodec.Decoder();

    /** meta 数组(枚举序号索引,镜像 Ignite);v1 预留。 */
    private final Object[] meta = new Object[16];

    private volatile boolean closed;

    public NioSession(SocketChannel ch, SelectionKey key) {
        this.ch = ch;
        this.key = key;
    }

    public SocketChannel channel() {
        return ch;
    }

    public SelectionKey key() {
        return key;
    }

    public ConcurrentLinkedQueue<ByteBuffer> writeQueue() {
        return writeQueue;
    }

    public FrameCodec.Decoder decoder() {
        return decoder;
    }

    public boolean isClosed() {
        return closed;
    }

    void markClosed() {
        closed = true;
    }

    public InetSocketAddress remoteAddress() {
        try {
            return (InetSocketAddress) ch.getRemoteAddress();
        } catch (Exception e) {
            return null;
        }
    }
}
