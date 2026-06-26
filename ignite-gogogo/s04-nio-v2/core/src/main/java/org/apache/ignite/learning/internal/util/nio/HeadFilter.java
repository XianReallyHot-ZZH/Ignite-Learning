package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;

/**
 * 链的 wire 侧端点(镜像 {@code HeadFilter}):
 * inbound 直通(从 wire 进入链);outbound 终结——把消息(ByteBuffer)入写队列并唤醒 owning worker。
 */
final class HeadFilter extends Filter {

    @Override
    void onInbound(NioSession ses, Object msg) {
        proceedIn(ses, msg);
    }

    @Override
    void onOutbound(NioSession ses, Object msg) {
        ses.writeQueue().offer((ByteBuffer) msg);
        ses.myWorker().wakeup();
    }
}
