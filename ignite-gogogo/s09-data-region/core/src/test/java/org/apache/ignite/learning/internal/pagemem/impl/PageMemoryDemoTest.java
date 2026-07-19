package org.apache.ignite.learning.internal.pagemem.impl;

import org.apache.ignite.learning.internal.pagemem.PageIdAllocator;
import org.apache.ignite.learning.internal.pagemem.PageMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * demo:分配大量页 → 释放 → 再分配,验证 free-list 复用(堆外内存不无限增长)(对应 S09 §5)。
 */
class PageMemoryDemoTest {
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
    void memoryNotUnbounded() {
        // 固定 8 页 region;若无 free-list 复用,第二轮分配必 OOM
        mem = newMem(8);
        for (int round = 0; round < 50; round++) {
            long[] ids = new long[8];
            for (int i = 0; i < 8; i++)
                ids[i] = mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
            for (int i = 0; i < 8; i++)
                mem.freePage(0, ids[i]);
        }
        assertEquals(0, mem.loadedPages(), "全释放后 loadedPages 应为 0(free-list 回收)");

        // free-list 复用后,仍可再分配 8 页(不依赖新堆外内存)
        for (int i = 0; i < 8; i++)
            mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        assertEquals(8, mem.loadedPages());
    }
}
