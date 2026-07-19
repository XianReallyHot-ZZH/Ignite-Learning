package org.apache.ignite.learning.internal.pagemem.impl;

import org.apache.ignite.learning.internal.pagemem.PageIdAllocator;
import org.apache.ignite.learning.internal.pagemem.PageIdUtils;
import org.apache.ignite.learning.internal.pagemem.PageMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 全局 Treiber free-list 单测:回收复用 + ABA 计数器(对应 S09 执行规格 §5)。
 */
class FreeListTest {
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
    void reuseAfterFree() {
        mem = newMem(8);
        long id1 = mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        int idx1 = PageIdUtils.pageIndex(id1);
        assertEquals(1, mem.loadedPages());

        mem.freePage(0, id1);
        assertEquals(0, mem.loadedPages(), "freePage 后 loadedPages 归零");

        long id2 = mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        assertEquals(idx1, PageIdUtils.pageIndex(id2), "释放后再分配应复用同一 pageIdx(free-list 回收)");
    }

    @Test
    void abaCounterMonotonic() {
        mem = newMem(8);
        PageMemoryNoStoreImpl pm = (PageMemoryNoStoreImpl)mem;

        // 分配 3 页,全释放入栈(head → c → b → a,LIFO)
        long a = pm.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        long b = pm.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        long c = pm.allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        pm.freePage(0, a);
        pm.freePage(0, b);
        pm.freePage(0, c);

        long cnt0 = (pm.freeListHeadValue() >>> 56) & 0xFF; // 高 8 位 = ABA 计数器

        // 连续 pop 三次(不再 push),计数器应单调 +1(防 ABA:同 relPtr 不同计数器让 CAS 失败)
        pm.allocatePage(0, 1, PageIdAllocator.FLAG_DATA); // pop c
        long cnt1 = (pm.freeListHeadValue() >>> 56) & 0xFF;
        pm.allocatePage(0, 1, PageIdAllocator.FLAG_DATA); // pop b
        long cnt2 = (pm.freeListHeadValue() >>> 56) & 0xFF;
        pm.allocatePage(0, 1, PageIdAllocator.FLAG_DATA); // pop a
        long cnt3 = (pm.freeListHeadValue() >>> 56) & 0xFF;

        assertEquals(cnt0 + 1, cnt1, "第一次 pop 计数器 +1");
        assertEquals(cnt1 + 1, cnt2, "第二次 pop 计数器 +1");
        assertEquals(cnt2 + 1, cnt3, "第三次 pop 计数器 +1");
    }
}
