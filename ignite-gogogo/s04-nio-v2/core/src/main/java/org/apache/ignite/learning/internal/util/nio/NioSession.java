package org.apache.ignite.learning.internal.util.nio;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 一个连接的会话状态 v2(镜像 {@code GridNioSession} / {@code GridSelectorNioSessionImpl})。
 * 相比 v1 多了 {@link FilterChain} 与所属 {@link ClientWorker}。
 * 同一会话的所有 IO 由其 owning worker 线程串行处理 → 会话内无锁。
 */
public final class NioSession {

    private final SocketChannel ch;
    private final SelectionKey key;
    private final FilterChain chain;
    private final ClientWorker myWorker;

    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();
    private final Object[] meta = new Object[16];
    private volatile boolean closed;

    public NioSession(SocketChannel ch, SelectionKey key, FilterChain chain, ClientWorker myWorker) {
        this.ch = ch;
        this.key = key;
        this.chain = chain;
        this.myWorker = myWorker;
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

    public ConcurrentLinkedQueue<ByteBuffer> writeQueue() {
        return writeQueue;
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
