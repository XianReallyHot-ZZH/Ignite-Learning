package org.apache.ignite.learning.internal.util.nio;

/**
 * 消费者注册的监听器(镜像 {@code GridNioServerListener<T>})。
 *
 * <p>S6 起泛化为 {@code <T>}:消息类型由过滤链决定 —— S3~S5 是 {@code byte[]},
 * S6 起可换成结构化 {@code Message}(经 {@code MessageCodecFilter} 解出)。
 *
 * <p>所有回调都在 NIO worker 线程触发,因此同会话内串行、无需加锁。
 *
 * @param <T> 经过滤链解出的消息类型
 */
public interface NioServerListener<T> {

    /** 连接建立(accept / register OP_READ 完成)。 */
    void onConnected(NioSession ses);

    /** 收到一条完整消息(已由过滤链解码为 T)。 */
    void onMessage(NioSession ses, T msg);

    /** 连接断开。 */
    void onDisconnected(NioSession ses);
}
