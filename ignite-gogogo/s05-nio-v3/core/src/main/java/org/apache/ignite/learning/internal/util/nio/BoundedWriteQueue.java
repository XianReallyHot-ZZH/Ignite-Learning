package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;

/**
 * 有界写队列 + 信号量(镜像 {@code GridSelectorNioSessionImpl.queue + sem})—— 发送背压。
 *
 * <ul>
 *   <li>{@code limit <= 0} = 无界(无信号量;默认,行为同普通队列,echo 等不受影响)。</li>
 *   <li>生产者({@link #offer})满则阻塞({@code acquireUninterruptibly});</li>
 *   <li><b>message-thread 旁路</b>:worker 线程(处理消息时)发送,跳过信号量 —— 防"worker 既处理消息
 *       又排空写队列"时自死锁。为避免信号量错账,{@link #poll()} 释放时对跳过的项也跳过(<b>对称</b>)。</li>
 * </ul>
 */
final class BoundedWriteQueue {

    /** 队列项:带上 bypass 标记,记录投入它的线程是否为 message-thread(poll 据此决定是否释放信号量)。 */
    private static final class Node {
        final ByteBuffer buf;
        final boolean bypass;

        Node(ByteBuffer b, boolean bp) {
            buf = b;
            bypass = bp;
        }
    }

    private final Deque<Node> q = new ConcurrentLinkedDeque<>();
    private final Semaphore sem; // null = 无界

    BoundedWriteQueue(int limit) {
        this.sem = limit > 0 ? new Semaphore(limit) : null;
    }

    /** 生产者入队:非 message-thread 满则阻塞(message-thread 旁路防死锁)。 */
    void offer(ByteBuffer buf) {
        boolean bypass = GridBackPressureControl.inMessageThread();
        if (sem != null && !bypass) {
            sem.acquireUninterruptibly(); // 队列满则在此阻塞,直到有 poll 释放
        }
        q.offer(new Node(buf, bypass));
    }

    ByteBuffer peek() {
        Node n = q.peek();
        return n == null ? null : n.buf;
    }

    /**
     * 取出队首。<b>对称释放</b>:仅当该项是"非 message-thread 投入的"(即 acquire 计过信号量)才 release。
     * 这样 bypass 项入队没 acquire、出队也不 release → 信号量账目始终平衡,无泄漏。
     */
    ByteBuffer poll() {
        Node n = q.poll();
        if (n == null) {
            return null;
        }
        if (sem != null && !n.bypass) {
            sem.release();
        }
        return n.buf;
    }

    boolean isEmpty() {
        return q.isEmpty();
    }

    int size() {
        return q.size();
    }
}
