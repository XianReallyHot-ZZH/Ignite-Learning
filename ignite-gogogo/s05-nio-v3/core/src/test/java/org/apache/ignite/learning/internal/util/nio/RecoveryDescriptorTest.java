package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RecoveryDescriptor 纯逻辑测试:计数器、未确认队列、ack、握手对齐、溢出、release 语义。 */
class RecoveryDescriptorTest {

    private static ByteBuffer msg(String s) {
        return ByteBuffer.wrap(s.getBytes());
    }

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

    @Test
    void overflowTriggersReconnect() {
        RecoveryDescriptor rd = new RecoveryDescriptor(2);
        assertTrue(rd.add(msg("a")));  // size 1
        assertTrue(rd.add(msg("b")));  // size 2(到限)
        assertFalse(rd.add(msg("c"))); // size 3 → 溢出 → false(应触发重连)
        assertEquals(3, rd.unackedCount());
    }

    @Test
    void releaseSemantics() {
        // 节点离开 → 排空(fail 回调)
        RecoveryDescriptor left = new RecoveryDescriptor(10);
        left.add(msg("a"));
        left.add(msg("b"));
        List<ByteBuffer> drained = left.release(true);
        assertEquals(2, drained.size());
        assertEquals(0, left.unackedCount());

        // 仍活 → 保留待重发
        RecoveryDescriptor alive = new RecoveryDescriptor(10);
        alive.add(msg("a"));
        alive.add(msg("b"));
        List<ByteBuffer> kept = alive.release(false);
        assertTrue(kept.isEmpty());
        assertEquals(2, alive.unackedCount());
    }
}
