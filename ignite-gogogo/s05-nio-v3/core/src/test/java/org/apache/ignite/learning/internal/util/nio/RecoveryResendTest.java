package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 断线重连重发 + 计数器去重的端到端模拟(不依赖真实 socket,纯用 RecoveryDescriptor 的计数器推演)。
 *
 * <p>场景:发送方发 m1,m2,m3;接收方收到 m1,m2(m3 断线丢失)→ 重连握手(接收方回告"收到 2")
 * → 发送方对齐、只重发 m3 → 接收方收 m3 → 共 m1,m2,m3,<b>无重复</b>。</p>
 */
class RecoveryResendTest {

    private static ByteBuffer msg(String s) {
        return ByteBuffer.wrap(s.getBytes());
    }

    @Test
    void reconnectResendsAndDedups() {
        // 发送方:发出 m1,m2,m3(均未确认)
        RecoveryDescriptor sender = new RecoveryDescriptor(10);
        sender.add(msg("m1"));
        sender.add(msg("m2"));
        sender.add(msg("m3"));

        // 接收方:收到 m1,m2(m3 在断线时丢失)
        RecoveryDescriptor receiver = new RecoveryDescriptor(10);
        receiver.received();
        receiver.received();
        assertEquals(2, receiver.receivedCount());

        // 断线 → 重连握手:发送方按"接收方已收数"对齐,拿到需重发列表
        List<ByteBuffer> toResend = sender.onHandshake(receiver.receivedCount()); // =2 → 重发 m3
        assertEquals(1, toResend.size());

        // 重发的 m3 抵达接收方(m1/m2 不会再发 → 无重复)
        receiver.received();
        assertEquals(3, receiver.receivedCount()); // m1,m2,m3
    }
}
