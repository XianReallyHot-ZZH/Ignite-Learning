package org.apache.ignite.learning.internal.util.nio;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 一个连接的会话状态 v2(镜像 {@code GridNioSession} / {@code GridSelectorNioSessionImpl})。
 *
 * <p>相比 v1 多了 {@link FilterChain}(取代 v1 的直连 listener)与所属 {@link ClientWorker}。
 * 同一会话的所有 IO 由其 owning worker 线程串行处理 → <b>会话内无锁</b>。</p>
 */
public final class NioSession {

    private final SocketChannel ch;
    private final SelectionKey key;
    /** 本会话专属的双向过滤链(各会话各一份,保证 codec 的 Decoder 独享)。 */
    private final FilterChain chain;
    /** 本会话所属的 worker(注册时绑定,不变);send 经它唤醒、pause/resume 经它翻 interestOps。 */
    private final ClientWorker myWorker;

    /** 写队列(pull-based:任意线程 offer,本会话 worker 线程 poll+write)。并发集合只为跨线程入队安全。 */
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();
    /** meta 数组(枚举序号索引,零分配,镜像 Ignite 习惯);v2 预留。 */
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

    /** 本会话所属 worker 的编号(测试里用来观测连接是否分散到不同 worker)。 */
    public int workerId() {
        return myWorker.id();
    }

    /** 写队列(HeadFilter outbound 终结处 offer;ClientWorker.write poll+write)。 */
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

    /** 本会话的过滤链(发送/接收都经它)。 */
    FilterChain chain() {
        return chain;
    }

    /** 所属 worker(send 经它唤醒、pause/resume 经它翻 interestOps)。 */
    ClientWorker myWorker() {
        return myWorker;
    }

    void markClosed() {
        closed = true;
    }
}
