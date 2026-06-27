package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;

/**
 * 链的 <b>wire 侧端点</b>(镜像 {@code HeadFilter}):
 * <ul>
 *   <li><b>inbound</b>:从 wire 进入链的起点,直通(proceedIn 向 app);</li>
 *   <li><b>outbound</b>:出站终点——把已编码的 {@link ByteBuffer} 入写队列并唤醒 owning worker
 *       (pull-based:真正 channel.write 由 worker 在 OP_WRITE 就绪时完成)。</li>
 * </ul>
 */
final class HeadFilter extends Filter {

    /** inbound:wire 字节进入链,直通向 app(codec/tail/listener)。 */
    @Override
    void onInbound(NioSession ses, Object msg) {
        proceedIn(ses, msg);
    }

    /**
     * outbound 终结:消息到这一路已是 ByteBuffer(codec 编码后)。
     * 入写队列 + 唤醒 owning worker;worker 在其线程 arm OP_WRITE → 真正 channel.write。
     */
    @Override
    void onOutbound(NioSession ses, Object msg) {
        ses.writeQueue().offer((ByteBuffer) msg);
        ses.myWorker().wakeup();
    }
}
