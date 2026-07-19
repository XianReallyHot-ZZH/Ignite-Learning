package org.apache.ignite.learning.internal.pagemem.impl;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ignite.learning.internal.pagemem.PageIdAllocator;
import org.apache.ignite.learning.internal.pagemem.PageIdUtils;
import org.apache.ignite.learning.internal.pagemem.PageMemory;
import org.apache.ignite.learning.internal.util.OffheapReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页内 8B 锁字 R/W 锁:读写互斥 + tag 陈旧检测 + upgrade(对应 S09 执行规格 §5)。
 */
class PageLockTest {
    private static final int PAGE_SIZE = 4096;

    private PageMemory mem;

    private PageMemory newMem(int pages) {
        PageMemory m = new PageMemoryNoStoreImpl(PAGE_SIZE, (long)PAGE_SIZE * pages);
        m.start();
        return m;
    }

    @AfterEach
    void tearDown() {
        if (mem != null)
            mem.stop();
    }

    @Test
    void readWriteMutex() throws Exception {
        mem = newMem(4);
        long id = mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        long page = mem.acquirePage(0, id);

        long wptr = mem.writeLock(0, id, page);
        assertNotEquals(0L, wptr);

        AtomicBoolean gotRead = new AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            long rptr = mem.readLock(0, id, page);
            gotRead.set(rptr != 0L);
            if (rptr != 0L)
                mem.readUnlock(0, id, page);
        });
        reader.start();
        Thread.sleep(150); // 等读线程进入 readLock(应被写锁阻塞,park)
        assertTrue(reader.isAlive(), "写锁持有时,读锁应阻塞");
        mem.writeUnlock(0, id, page, false);

        reader.join(3000);
        assertFalse(reader.isAlive(), "写锁释放后,读线程应退出");
        assertTrue(gotRead.get(), "写锁释放后,读锁应获取成功");
    }

    @Test
    void tagStaleDetection() {
        mem = newMem(4);
        long id = mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        long page = mem.acquirePage(0, id);

        // 模拟页被回收复用:rotatePageId 改 rotation → tag 变,但 pageIndex 不变(同一物理页)
        long staleId = PageIdUtils.rotatePageId(id);
        assertNotEquals(PageIdUtils.tag(id), PageIdUtils.tag(staleId));
        long stalePage = mem.acquirePage(0, staleId);
        assertEquals(page, stalePage, "pageIndex 不变 → 同一物理页");

        // 用陈旧 tag 锁 → 锁字里是原 tag → 不符 → 返回 0L(use-after-free 安全检测)
        long rptr = mem.readLock(0, staleId, stalePage);
        assertEquals(0L, rptr, "tag 不符(陈旧页)应返回 0L");
    }

    @Test
    void upgradeToWriteLock() {
        // PageMemory 接口不暴露 upgrade;直接测 OffheapReadWriteLock(内部类)
        OffheapReadWriteLock lock = new OffheapReadWriteLock(4);
        long addr = OffHeap.allocateMemory(8);
        try {
            int tag = 0x1234;
            lock.init(addr, tag);

            assertTrue(lock.readLock(addr, tag), "读锁应成功");
            assertTrue(lock.isReadLocked(addr));

            assertTrue(lock.upgradeToWriteLock(addr, tag), "唯一读者应原子升级为写锁");
            assertTrue(lock.isWriteLocked(addr), "升级后应为写锁态");

            lock.writeUnlock(addr, tag);
            assertFalse(lock.isWriteLocked(addr));
        } finally {
            OffHeap.freeMemory(addr);
        }
    }
}
