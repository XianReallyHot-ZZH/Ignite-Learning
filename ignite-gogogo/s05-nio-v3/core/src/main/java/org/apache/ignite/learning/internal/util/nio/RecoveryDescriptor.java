package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 每 connection 的恢复状态(镜像 {@code GridNioRecoveryDescriptor})。
 * 单调计数器(sentCnt / acked / rcvCnt)+ 有界未确认队列;断线重连后重放未确认消息。
 * 去重靠计数器(无 per-message 去重集):接收方把自己已收数(rcvCnt)在握手时告诉发送方,发送方据此对齐。
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

    /** 记录一条已发出(已编码)消息。返回 false = 未确认队列溢出(应触发重连)。 */
    synchronized boolean add(ByteBuffer encoded) {
        sentCnt++;
        unacked.offer(copy(encoded));
        return unacked.size() <= queueLimit;
    }

    /** 对方告诉我们它已收到我们 n 条 → 弹出已确认。 */
    synchronized void ackReceived(long n) {
        while (acked < n && !unacked.isEmpty()) {
            unacked.poll();
            acked++;
        }
        if (acked > n) {
            acked = n;
        }
    }

    /** 我们收到一条对方的消息 → rcvCnt++(握手里回告对方)。 */
    synchronized void received() {
        rcvCnt++;
    }

    synchronized long receivedCount() {
        return rcvCnt;
    }

    /** 重连握手:对方告诉我们它收到我们 peerReceived 条 → 对齐,返回需重发的(剩余未确认)。 */
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

    /** 断开时:nodeLeft=true → 排空(fail 回调用);false → 保留待重发。返回被排空的消息。 */
    synchronized List<ByteBuffer> release(boolean nodeLeft) {
        List<ByteBuffer> drained = new ArrayList<>();
        if (nodeLeft) {
            while (!unacked.isEmpty()) {
                drained.add(unacked.poll());
            }
        }
        return drained;
    }

    private static ByteBuffer copy(ByteBuffer src) {
        ByteBuffer c = ByteBuffer.allocate(src.remaining());
        c.put(src.duplicate());
        c.flip();
        return c;
    }
}
