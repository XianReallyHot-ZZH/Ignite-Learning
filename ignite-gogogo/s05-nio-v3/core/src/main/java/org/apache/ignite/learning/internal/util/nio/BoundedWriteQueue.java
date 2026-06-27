package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;

/**
 * 有界写队列 + 信号量(镜像 {@code GridSelectorNioSessionImpl.queue + sem})。
 * <ul>
 *   <li>{@code limit <= 0} = 无界(无信号量;默认,行为同普通队列,echo 等不受影响)。</li>
 *   <li>生产者满则阻塞({@code acquireUninterruptibly});{@code message-thread}
 *       ({@link GridBackPressureControl})旁路信号量防自死锁。</li>
 *   <li>{@link #poll()} 释放信号量时**对称跳过** message-thread 投入的项(它们 acquire 时也跳过)。</li>
 * </ul>
 */
final class BoundedWriteQueue {

    private static final class Node {
        final ByteBuffer buf;
        final boolean bypass; // 投入该项的线程是否为 message-thread(决定是否计信号量)

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

    void offer(ByteBuffer buf) {
        boolean bypass = GridBackPressureControl.inMessageThread();
        if (sem != null && !bypass) {
            sem.acquireUninterruptibly();
        }
        q.offer(new Node(buf, bypass));
    }

    ByteBuffer peek() {
        Node n = q.peek();
        return n == null ? null : n.buf;
    }

    /** 取出队首;若该条是非 message-thread 投入的,释放一个信号量。 */
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
