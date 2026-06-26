package org.apache.ignite.learning.internal.util.nio;

/**
 * 消费者注册的监听器(镜像 {@code GridNioServerListener})。
 * 所有回调都在 NIO worker 线程触发,因此同会话内串行、无需加锁。
 */
public interface NioServerListener {

    /** 连接建立(accept / register OP_READ 完成)。 */
    void onConnected(NioSession ses);

    /** 收到一条完整帧(已解码)。 */
    void onMessage(NioSession ses, byte[] msg);

    /** 连接断开。 */
    void onDisconnected(NioSession ses);
}
