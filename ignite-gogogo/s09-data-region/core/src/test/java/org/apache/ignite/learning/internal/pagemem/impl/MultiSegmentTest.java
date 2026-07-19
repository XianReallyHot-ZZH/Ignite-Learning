package org.apache.ignite.learning.internal.pagemem.impl;

import org.apache.ignite.learning.internal.mem.unsafe.UnsafeMemoryProvider;
import org.apache.ignite.learning.internal.pagemem.DataRegionConfiguration;
import org.apache.ignite.learning.internal.pagemem.DataRegionMetricsImpl;
import org.apache.ignite.learning.internal.pagemem.PageIdAllocator;
import org.apache.ignite.learning.internal.pagemem.PageMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 多段惰性增长:initialSize 起,maxSize 上限,末段满 addSegment(对应 S09 执行规格 §5)。
 */
class MultiSegmentTest {
    private static final int PAGE_SIZE = 4096;

    private PageMemory mem;

    @AfterEach
    void tearDown() {
        if (mem != null)
            mem.stop();
    }

    @Test
    void lazyGrowthBeyondInitial() {
        // initialSize=4 页,maxSize=8 页:首段 4 页,第 5 页触发 addSegment(第二段 4 页)
        DataRegionConfiguration cfg = new DataRegionConfiguration()
            .setInitialSize(4L * PAGE_SIZE)
            .setMaxSize(8L * PAGE_SIZE)
            .setPageSize(PAGE_SIZE);
        mem = new PageMemoryNoStoreImpl(cfg, new UnsafeMemoryProvider(), new DataRegionMetricsImpl());
        mem.start();

        // 首段 4 页
        for (int i = 0; i < 4; i++)
            mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        assertEquals(4, mem.loadedPages());

        // 超过 initialSize → 必然 addSegment(否则首段满抛 OOM)
        mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        assertEquals(5, mem.loadedPages());

        // 继续到 maxSize = 8 页
        for (int i = 0; i < 3; i++)
            mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        assertEquals(8, mem.loadedPages());

        // 第 9 页 → maxSize 用尽 + free-list 空 → OOM
        assertThrows(PageMemoryOutOfMemoryException.class,
            () -> mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA));
    }
}
