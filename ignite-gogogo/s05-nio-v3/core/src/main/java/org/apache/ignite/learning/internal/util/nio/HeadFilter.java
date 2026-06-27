package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;

/**
 * 链的 wire 侧端点(镜像 {@code HeadFilter}):
 * inbound 直通;outbound 终结 —— recovery 记录未确认(若启用)+ 入有界写队列 + 唤醒 owning worker。
 */
final class HeadFilter extends Filter {

    @Override
    void onInbound(NioSession ses, Object msg) {
        proceedIn(ses, msg);
    }

    @Override
    void onOutbound(NioSession ses, Object msg) {
        ByteBuffer encoded = (ByteBuffer) msg;
        RecoveryDescriptor rd = ses.recoveryDescriptor();
        if (rd != null) {
            if (!rd.add(encoded.duplicate())) {
                ses.triggerReconnect(); // 未确认队列溢出 → 关闭,触发重连
            }
        }
        ses.offerFuture(encoded);
        ses.myWorker().wakeup();
    }
}
