package org.apache.ignite.learning.internal.util;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.ignite.learning.internal.pagemem.impl.OffHeap;

/**
 * 页内 8 字节锁字的条带读写锁(镜像 Ignite {@code internal/util/OffheapReadWriteLock})。
 *
 * <p><b>锁字 8B 布局</b>(LSB→MSB):
 * <pre>
 *  | WRITE_WAIT_CNT(16) | READ_WAIT_CNT(16) | TAG(16) | LOCK_CNT(16) |
 *  |      bits 48-63    |     bits 32-47     | bits16-31|   bits 0-15   |
 * </pre>
 * <ul>
 *   <li>{@code LOCK_CNT}(signed short):0=自由;-1=写锁(独占);&gt;0=N 个读者。</li>
 *   <li>{@code TAG}:16-bit 回收代。锁时校验 {@code tag(pageId)==锁字 TAG};不符 → 页已被复用(use-after-free),返回 false。</li>
 *   <li>{@code READ_WAIT_CNT}/{@code WRITE_WAIT_CNT}:等待者计数,用于 unlock 后唤醒。</li>
 * </ul>
 *
 * <p><b>条带</b>:{@code ReentrantLock[concLvl]} + read/write {@link Condition},仅用于争用时的 park/wake
 * (power-of-2,地址哈希到条带);锁状态本身在页内 8B 锁字(CAS 推进)。
 *
 * <p><b>流程</b>:spin {@code SPIN_CNT=32} 次CAS抢锁 → 失败退化条带 lock + 加 waiter + {@code await}。
 *
 * <p><b>简化(标 deferred)</b>:不做 write-waiter 公平优先抢占 / 随机唤醒策略({@code IGNITE_OFFHEAP_RANDOM_RW_POLICY})
 * / 完整 signalNextWaiter 公平链;unlock 后简单 signalAll(写优先)。
 */
public class OffheapReadWriteLock {
    public static final int DFLT_SPIN_COUNT = 32;

    public static final int SPIN_CNT = DFLT_SPIN_COUNT;

    public static final int LOCK_SIZE = 8;

    public static final int MAX_WAITERS = 0xFFFF;

    private static final long WORD_MASK = 0xFFFFL;

    private static final int TAG_SHIFT = 16;

    private static final int READ_WAIT_SHIFT = 32;

    private static final int WRITE_WAIT_SHIFT = 48;

    private final ReentrantLock[] locks;

    private final Condition[] readConditions;

    private final Condition[] writeConditions;

    private final int monitorsMask;

    /**
     * @param concLvl 条带数,须为 2 的幂(Ignite 默认 {@code nearestPow2(4*cores)})。
     */
    public OffheapReadWriteLock(int concLvl) {
        if (concLvl <= 0 || (concLvl & (concLvl - 1)) != 0)
            throw new IllegalArgumentException("concLvl 须为 2 的幂: " + concLvl);

        monitorsMask = concLvl - 1;
        locks = new ReentrantLock[concLvl];
        readConditions = new Condition[concLvl];
        writeConditions = new Condition[concLvl];

        for (int i = 0; i < concLvl; i++) {
            ReentrantLock lock = new ReentrantLock();
            locks[i] = lock;
            readConditions[i] = lock.newCondition();
            writeConditions[i] = lock.newCondition();
        }
    }

    /**
     * 初始化页头锁字(LOCK_CNT=0, TAG=tag)。镜像 Ignite {@code init(lock, tag)=putLong(lock, (long)tag<<16)}。
     */
    public void init(long lock, int tag) {
        tag &= 0xFFFF;
        if (tag == 0)
            throw new IllegalArgumentException("tag 不可为 0(0 = 未初始化哨兵)");
        OffHeap.putLong(lock, (long)tag << TAG_SHIFT);
    }

    // ---- 锁字段提取 / 拼装(bit helpers)----

    private static short lockCount(long state) {
        return (short)(state & WORD_MASK); // signed:0xFFFF → -1(写锁)
    }

    private static int tag(long state) {
        return (int)((state >>> TAG_SHIFT) & WORD_MASK);
    }

    private static int readersWaitCount(long state) {
        return (int)((state >>> READ_WAIT_SHIFT) & WORD_MASK);
    }

    private static int writersWaitCount(long state) {
        return (int)((state >>> WRITE_WAIT_SHIFT) & WORD_MASK);
    }

    private static boolean checkTag(long state, int tag) {
        return tag(state) == tag;
    }

    private static boolean canReadLock(long state) {
        return lockCount(state) >= 0; // 无写锁(写锁时 lockCount=-1)
    }

    private static boolean canWriteLock(long state) {
        return lockCount(state) == 0; // 完全自由
    }

    /** 把 delta 加到 lockCnt / readWait / writeWait,tag 不变,拼回 long。 */
    private static long updateState(long state, int lockCntDelta, int readWaitDelta, int writeWaitDelta) {
        long lc = (lockCount(state) + lockCntDelta) & WORD_MASK;
        long rw = (readersWaitCount(state) + readWaitDelta) & WORD_MASK;
        long ww = (writersWaitCount(state) + writeWaitDelta) & WORD_MASK;
        long tg = tag(state);
        return lc | ((long)tg << TAG_SHIFT) | (rw << READ_WAIT_SHIFT) | (ww << WRITE_WAIT_SHIFT);
    }

    /** 地址哈希到条带(8 字节对齐地址 >> 3)。 */
    private int lockIndex(long lock) {
        return (int)((lock >>> 3) & monitorsMask);
    }

    // ---- 读锁 ----

    public boolean readLock(long lock, int tag) {
        for (int i = 0; i < SPIN_CNT; i++) {
            long state = OffHeap.getLongVolatile(lock);
            if (!checkTag(state, tag))
                return false; // 陈旧页
            if (canReadLock(state)
                && OffHeap.compareAndSwapLong(lock, state, updateState(state, 1, 0, 0)))
                return true;
        }

        int idx = lockIndex(lock);
        ReentrantLock lockObj = locks[idx];
        lockObj.lock();
        try {
            updateReadersWaitCount(lock, 1); // 先登记 read waiter
            return waitAcquireReadLock(lock, idx, tag);
        } finally {
            lockObj.unlock();
        }
    }

    private boolean waitAcquireReadLock(long lock, int idx, int tag) {
        while (true) {
            long state = OffHeap.getLongVolatile(lock);
            if (!checkTag(state, tag)) {
                // tag 失效:退 read waiter,唤醒别人,返回 false。
                if (OffHeap.compareAndSwapLong(lock, state, updateState(state, 0, -1, 0))) {
                    signalWaiters(state, idx);
                    return false;
                }
            } else if (canReadLock(state)) {
                // 抢读锁:lockCnt+1, readWait-1。
                if (OffHeap.compareAndSwapLong(lock, state, updateState(state, 1, -1, 0)))
                    return true;
            } else {
                readConditions[idx].awaitUninterruptibly();
            }
        }
    }

    public void readUnlock(long lock) {
        while (true) {
            long state = OffHeap.getLongVolatile(lock);
            if (lockCount(state) <= 0)
                throw new IllegalMonitorStateException("readUnlock 但未持有读锁: state=0x" + Long.toHexString(state));
            long updated = updateState(state, -1, 0, 0);
            if (OffHeap.compareAndSwapLong(lock, state, updated)) {
                // 到 0 且有写 waiter → 唤醒写。
                if (lockCount(updated) == 0 && writersWaitCount(updated) > 0) {
                    int idx = lockIndex(lock);
                    locks[idx].lock();
                    try {
                        writeConditions[idx].signalAll();
                    } finally {
                        locks[idx].unlock();
                    }
                }
                return;
            }
        }
    }

    // ---- 写锁 ----

    public boolean tryWriteLock(long lock, int tag) {
        long state = OffHeap.getLongVolatile(lock);
        return checkTag(state, tag) && canWriteLock(state)
            && OffHeap.compareAndSwapLong(lock, state, updateState(state, -1, 0, 0));
    }

    public boolean writeLock(long lock, int tag) {
        for (int i = 0; i < SPIN_CNT; i++) {
            long state = OffHeap.getLongVolatile(lock);
            if (!checkTag(state, tag))
                return false; // 陈旧页
            if (canWriteLock(state)
                && OffHeap.compareAndSwapLong(lock, state, updateState(state, -1, 0, 0)))
                return true;
        }

        int idx = lockIndex(lock);
        ReentrantLock lockObj = locks[idx];
        lockObj.lock();
        try {
            updateWritersWaitCount(lock, 1); // 先登记 write waiter
            return waitAcquireWriteLock(lock, idx, tag);
        } finally {
            lockObj.unlock();
        }
    }

    private boolean waitAcquireWriteLock(long lock, int idx, int tag) {
        while (true) {
            long state = OffHeap.getLongVolatile(lock);
            if (!checkTag(state, tag)) {
                if (OffHeap.compareAndSwapLong(lock, state, updateState(state, 0, 0, -1))) {
                    signalWaiters(state, idx);
                    return false;
                }
            } else if (canWriteLock(state)) {
                if (OffHeap.compareAndSwapLong(lock, state, updateState(state, -1, 0, -1)))
                    return true;
            } else {
                writeConditions[idx].awaitUninterruptibly();
            }
        }
    }

    public void writeUnlock(long lock, int tag) {
        long updated = 0;
        while (true) {
            long state = OffHeap.getLongVolatile(lock);
            if (lockCount(state) != -1)
                throw new IllegalMonitorStateException("writeUnlock 但未持有写锁: state=0x" + Long.toHexString(state));
            updated = updateState(state, 1, 0, 0); // -1 + 1 = 0(自由)
            if (OffHeap.compareAndSwapLong(lock, state, updated))
                break;
        }
        // 唤醒等待者(简化:写优先,无写则读;镜像 Ignite 默认 signal-to-writers)。
        int writeWait = writersWaitCount(updated);
        int readWait = readersWaitCount(updated);
        if (writeWait > 0 || readWait > 0) {
            int idx = lockIndex(lock);
            locks[idx].lock();
            try {
                if (writeWait > 0)
                    writeConditions[idx].signalAll();
                else
                    readConditions[idx].signalAll();
            } finally {
                locks[idx].unlock();
            }
        }
    }

    // ---- 升级 ----

    /**
     * 读锁升级写锁。返回 {@code true}=原子升级成功(本线程是唯一读者);{@code false}=失败
     * (tag 失效或非独占,此时<b>已释放读锁</b>,调用方须重新获取写锁并重验页状态)。
     */
    public boolean upgradeToWriteLock(long lock, int tag) {
        for (int i = 0; i < SPIN_CNT; i++) {
            long state = OffHeap.getLongVolatile(lock);
            if (!checkTag(state, tag)) {
                readUnlock(lock);
                return false;
            }
            if (lockCount(state) == 1) {
                // 唯一读者 → 升级:LOCK_CNT 1 → -1(delta=-2)。
                if (OffHeap.compareAndSwapLong(lock, state, updateState(state, -2, 0, 0)))
                    return true;
            }
        }
        // spin 后仍未独占升级:释放读锁,让调用方走 writeLock 重试(简化,不做 Ignite 的"退读+加写 waiter park")。
        readUnlock(lock);
        return false;
    }

    // ---- 查询 ----

    public boolean isWriteLocked(long lock) {
        return lockCount(OffHeap.getLongVolatile(lock)) == -1;
    }

    public boolean isReadLocked(long lock) {
        return lockCount(OffHeap.getLongVolatile(lock)) > 0;
    }

    // ---- waiter 计数 CAS ----

    private void updateReadersWaitCount(long lock, int delta) {
        while (true) {
            long state = OffHeap.getLongVolatile(lock);
            if (OffHeap.compareAndSwapLong(lock, state, updateState(state, 0, delta, 0)))
                return;
        }
    }

    private void updateWritersWaitCount(long lock, int delta) {
        while (true) {
            long state = OffHeap.getLongVolatile(lock);
            if (OffHeap.compareAndSwapLong(lock, state, updateState(state, 0, 0, delta)))
                return;
        }
    }

    /** unlock 后唤醒(读或写 waiter,取决于谁在等;简化:有写优先写,否则读)。 */
    private void signalWaiters(long state, int idx) {
        int writeWait = writersWaitCount(state);
        int readWait = readersWaitCount(state);
        if (writeWait == 0 && readWait == 0)
            return;
        if (writeWait > 0)
            writeConditions[idx].signalAll();
        else
            readConditions[idx].signalAll();
    }
}
