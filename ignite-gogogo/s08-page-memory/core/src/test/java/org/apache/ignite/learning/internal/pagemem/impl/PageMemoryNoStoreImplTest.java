package org.apache.ignite.learning.internal.pagemem.impl;

import java.nio.ByteBuffer;
import org.apache.ignite.learning.internal.pagemem.PageIdAllocator;
import org.apache.ignite.learning.internal.pagemem.PageIdUtils;
import org.apache.ignite.learning.internal.pagemem.PageMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PageMemoryNoStoreImpl} 单段页内存单测:分配/读写/页头布局/段满 OOM(对应 S08 执行规格 §5)。
 */
class PageMemoryNoStoreImplTest {

    private static final int PAGE_SIZE = 4096;

    private static final int TOTAL_PAGES = 8;

    private PageMemory mem;

    private PageMemory newMem() {
        PageMemory m = new PageMemoryNoStoreImpl(PAGE_SIZE, (long)PAGE_SIZE * TOTAL_PAGES);
        m.start();
        return m;
    }

    @AfterEach
    void tearDown() {
        if (mem != null)
            mem.stop();
    }

    @Test
    void allocateAndPageBufferRoundtrip() {
        mem = newMem();
        long pageId = mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        long page = mem.acquirePage(0, pageId);

        int dataOff = PageMemoryNoStoreImpl.PAGE_OVERHEAD;
        long magic = 0xCAFEBABEDEAD1234L;

        ByteBuffer buf = mem.pageBuffer(page);
        buf.putLong(dataOff, magic);
        assertEquals(magic, buf.getLong(dataOff));

        // pageBuffer 真的 wrap 了那块堆外:OffHeap 直读同地址看到一致数据
        assertEquals(magic, OffHeap.getLong(page + PageMemoryNoStoreImpl.PAGE_OVERHEAD));
    }

    @Test
    void allocateManyPages() {
        mem = newMem();
        long[] ids = new long[TOTAL_PAGES];
        for (int i = 0; i < TOTAL_PAGES; i++) {
            ids[i] = mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
            assertEquals(i, PageIdUtils.pageIndex(ids[i]), "pageIdx 单调递增");
        }
        assertEquals(TOTAL_PAGES, mem.loadedPages());

        // 各页 pageBuffer 不重叠:页 i 写独有值,页 j 不受影响
        long base = 0x1122334455667788L;
        int dataOff = PageMemoryNoStoreImpl.PAGE_OVERHEAD;
        long[] pages = new long[TOTAL_PAGES];
        for (int i = 0; i < TOTAL_PAGES; i++) {
            pages[i] = mem.acquirePage(0, ids[i]);
            mem.pageBuffer(pages[i]).putLong(dataOff, base + i);
        }
        for (int i = 0; i < TOTAL_PAGES; i++)
            assertEquals(base + i, mem.pageBuffer(pages[i]).getLong(dataOff), "页 " + i + " 数据独立");
    }

    @Test
    void pageHeaderLayout() {
        mem = newMem();
        long pageId = mem.allocatePage(0, 7, PageIdAllocator.FLAG_IDX);
        long page = mem.acquirePage(0, pageId);
        ByteBuffer buf = mem.pageBuffer(page);

        // offset 0-7:PAGE_MARKER
        assertEquals(PageMemoryNoStoreImpl.PAGE_MARKER, buf.getLong(0));
        // offset 8-15:pageId
        assertEquals(pageId, buf.getLong(PageMemoryNoStoreImpl.PAGE_ID_OFFSET));
        // PAGE_OVERHEAD = LOCK_OFFSET(16) + LOCK_SIZE(8) = 24
        assertEquals(24, PageMemoryNoStoreImpl.PAGE_OVERHEAD);
    }

    @Test
    void outOfMemoryWhenFull() {
        mem = newMem();
        for (int i = 0; i < TOTAL_PAGES; i++)
            mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        assertEquals(TOTAL_PAGES, mem.loadedPages());

        // 第 TOTAL_PAGES+1 页 → 段满抛 OOM(S8 无 free-list)
        assertThrows(PageMemoryOutOfMemoryException.class,
            () -> mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA));
        assertEquals(TOTAL_PAGES, mem.loadedPages(), "满后 loadedPages 不变");
    }
}
