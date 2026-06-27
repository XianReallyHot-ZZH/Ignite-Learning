package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;

/**
 * 链的 <b>wire 侧端点</b>(镜像 {@code HeadFilter}),v3:
 * <ul>
 *   <li><b>inbound</b>:wire 进入链的起点,直通向 app;</li>
 *   <li><b>outbound</b>:出站终点 —— 若启用 recovery 先记录未确认(溢出则触发重连),再入有界写队列 + 唤醒 owning worker。</li>
 * </ul>
 */
final class HeadFilter extends Filter {

    /** inbound:wire 字节进入链,直通向 app。 */
    @Override
    void onInbound(NioSession ses, Object msg) {
        proceedIn(ses, msg);
    }

    /**
     * outbound 终结:消息到这一路已是 ByteBuffer(codec 编码后)。
     * 若会话挂了 RecoveryDescriptor,先把它计入未确认队列(duplicate 一份,原 buf 继续入写队列);
     * 溢出(对端久不 ack)→ 关连接触发重连。然后入写队列 + 唤醒 owning worker。
     */
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
