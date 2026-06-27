package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecoveryDescriptor} 纯逻辑测试(不依赖网络)。
 * 覆盖:计数器 + 未确认队列、ack 弹出、握手对齐、队列溢出、release(drain vs 保留)语义。
 */
class RecoveryDescriptorTest {

    /** 工具:把字符串包成 ByteBuffer(模拟一条已编码消息)。 */
    private static ByteBuffer msg(String s) {
        return ByteBuffer.wrap(s.getBytes());
    }

    /** 基本:发 3 条 → sentCnt=3、unacked=3、acked=0。 */
    @Test
    void countersAndUnackedQueue() {
        RecoveryDescriptor rd = new RecoveryDescriptor(10);
        rd.add(msg("a"));
        rd.add(msg("b"));
        rd.add(msg("c"));
        assertEquals(3, rd.sentCount());
        assertEquals(3, rd.unackedCount());
        assertEquals(0, rd.ackedCount());
    }

    /** ack:对方确认收到 2 条 → 弹出 2 条已确认,unacked 剩 1。 */
    @Test
    void ackReceivedDropsAcked() {
        RecoveryDescriptor rd = new RecoveryDescriptor(10);
        rd.add(msg("a"));
        rd.add(msg("b"));
        rd.add(msg("c"));
        rd.ackReceived(2);
        assertEquals(2, rd.ackedCount());
        assertEquals(1, rd.unackedCount());
    }

    /** 握手对齐:对方说"收到 1"→ 丢 a,返回需重发的 b,c(2 条);acked=1、unacked=2。 */
    @Test
    void onHandshakeAligns() {
        RecoveryDescriptor rd = new RecoveryDescriptor(10);
        rd.add(msg("a"));
        rd.add(msg("b"));
        rd.add(msg("c")); // 未确认:a,b,c
        List<ByteBuffer> toResend = rd.onHandshake(1); // 对方收到 1(a)→ 对齐,需重发 b,c
        assertEquals(2, toResend.size());
        assertEquals(1, rd.ackedCount());
        assertEquals(2, rd.unackedCount());
    }

    /** 溢出:queueLimit=2,第 3 条 add 返回 false(应触发重连)。 */
    @Test
    void overflowTriggersReconnect() {
        RecoveryDescriptor rd = new RecoveryDescriptor(2);
        assertTrue(rd.add(msg("a")));  // size 1 → true
        assertTrue(rd.add(msg("b")));  // size 2(到限)→ true
        assertFalse(rd.add(msg("c"))); // size 3 → 溢出 → false(应触发重连)
        assertEquals(3, rd.unackedCount());
    }

    /** release 语义:节点离开 → 排空(fail 回调);仅断线、对端仍活 → 保留待重发。 */
    @Test
    void releaseSemantics() {
        // 节点离开 → 排空(调用方逐条 fail)
        RecoveryDescriptor left = new RecoveryDescriptor(10);
        left.add(msg("a"));
        left.add(msg("b"));
        List<ByteBuffer> drained = left.release(true);
        assertEquals(2, drained.size());
        assertEquals(0, left.unackedCount());

        // 仍活 → 保留待重连重发
        RecoveryDescriptor alive = new RecoveryDescriptor(10);
        alive.add(msg("a"));
        alive.add(msg("b"));
        List<ByteBuffer> kept = alive.release(false);
        assertTrue(kept.isEmpty());
        assertEquals(2, alive.unackedCount());
    }
}
