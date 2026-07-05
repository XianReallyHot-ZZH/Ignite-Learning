package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 每 connection 的恢复状态(镜像 {@code GridNioRecoveryDescriptor})。
 *
 * <p>核心是三个单调计数器 + 一个有界未确认队列:
 * <ul>
 *   <li>{@code sentCnt}:本端累计已发;</li>
 *   <li>{@code acked}:对端已确认收到(= 对端对我们方向的 rcvCnt);</li>
 *   <li>{@code rcvCnt}:本端已从对端收到(握手里回告对端,让其据此对齐);</li>
 *   <li>{@code unacked}:已发但尚未被对端确认的消息(= sentCnt − acked),有界,溢出触发重连。</li>
 * </ul>
 * <p><b>去重靠计数器</b>(无 per-message 去重集):重连握手时双方用"已收数"对齐,
 * 发送方据此丢弃已确认、只重发剩余 → 天然不重复。</p>
 */
final class RecoveryDescriptor {

    private final int queueLimit;
    private final Deque<ByteBuffer> unacked = new ArrayDeque<>();
    private long sentCnt = 0;
    private long acked = 0;  // 对方已确认收到(= 对方对我们方向的 rcvCnt)
    private long rcvCnt = 0; // 我们已从对方收到(握手时回告对方)

    RecoveryDescriptor(int queueLimit) {
        this.queueLimit = queueLimit;
    }

    /**
     * 记录一条已发出(已编码)消息。copy 一份入未确认队列(原 buf 继续走写队列)。
     * @return false = 未确认队列溢出(对端久不 ack,应触发重连)
     */
    synchronized boolean add(ByteBuffer encoded) {
        sentCnt++;
        unacked.offer(copy(encoded));
        return unacked.size() <= queueLimit; // 超过 queueLimit → 溢出
    }

    /** 对端告诉我们它已收到我们 n 条 → 把已确认的从未确认队列弹出。 */
    synchronized void ackReceived(long n) {
        while (acked < n && !unacked.isEmpty()) {
            unacked.poll();
            acked++;
        }
        if (acked > n) {
            acked = n; // 防御:不应出现对端回告数 < 已 ack 数
        }
    }

    /** 我们收到一条对方的消息 → rcvCnt++(握手里回告对方,让其据此对齐)。 */
    synchronized void received() {
        rcvCnt++;
    }

    synchronized long receivedCount() {
        return rcvCnt;
    }

    /**
     * 重连握手:对端告诉我们它收到我们 peerReceived 条 → 先 ackReceived 对齐(丢已确认),
     * 再把剩余未确认的消息 copy 一份返回(供重发)。原队列保留,便于再次断线时仍能重发。
     */
    synchronized List<ByteBuffer> onHandshake(long peerReceived) {
        ackReceived(peerReceived);
        List<ByteBuffer> toResend = new ArrayList<>();
        for (ByteBuffer b : unacked) {
            toResend.add(copy(b));
        }
        return toResend;
    }

    synchronized int unackedCount() {
        return unacked.size();
    }

    synchronized long sentCount() {
        return sentCnt;
    }

    synchronized long ackedCount() {
        return acked;
    }

    /**
     * 断开时的清理。
     * @param nodeLeft true=对端已离开 → 排空未确认队列(逐条 fail 回调);false=仅断线、对端仍在 → 保留待重连重发。
     * @return 被排空的消息(nodeLeft=false 时为空)
     */
    synchronized List<ByteBuffer> release(boolean nodeLeft) {
        List<ByteBuffer> drained = new ArrayList<>();
        if (nodeLeft) {
            while (!unacked.isEmpty()) {
                drained.add(unacked.poll());
            }
        }
        return drained;
    }

    /** 复制一个内容相同、position 归零的新 ByteBuffer(重发时需独立 position,不污染原 buf)。 */
    private static ByteBuffer copy(ByteBuffer src) {
        ByteBuffer c = ByteBuffer.allocate(src.remaining());
        c.put(src.duplicate()); // duplicate:独立 position,不动 src
        c.flip();
        return c;
    }
}
