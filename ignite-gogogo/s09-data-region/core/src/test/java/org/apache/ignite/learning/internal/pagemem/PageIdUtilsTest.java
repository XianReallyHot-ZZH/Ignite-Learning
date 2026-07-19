package org.apache.ignite.learning.internal.pagemem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PageIdUtils} 位运算往返 / 边界 / effectivePageId / rotatePageId 单测(对应 S08 执行规格 §5)。
 */
class PageIdUtilsTest {

    @Test
    void encodeDecodeRoundtrip() {
        int partId = 42;
        byte flag = PageIdAllocator.FLAG_DATA;
        int pageIdx = 12345;

        long id = PageIdUtils.pageId(partId, flag, pageIdx);

        assertEquals(pageIdx, PageIdUtils.pageIndex(id));
        assertEquals(partId, PageIdUtils.partId(id));
        assertEquals(flag, PageIdUtils.flag(id));
    }

    @Test
    void boundaryValues() {
        // 全 0
        long id0 = PageIdUtils.pageId(0, (byte)0, 0);
        assertEquals(0, PageIdUtils.pageIndex(id0));
        assertEquals(0, PageIdUtils.partId(id0));
        assertEquals((byte)0, PageIdUtils.flag(id0));

        // partId 最大、flag 全 1、pageIdx 最大正数
        long idMax = PageIdUtils.pageId(PageIdUtils.MAX_PART_ID, (byte)0xFF, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, PageIdUtils.pageIndex(idMax));
        assertEquals(PageIdUtils.MAX_PART_ID, PageIdUtils.partId(idMax));
        assertEquals((byte)0xFF, PageIdUtils.flag(idMax));

        // pageIdx 占低 32 位,大 pageIdx 不得渗入 partId 段
        long idBigIdx = PageIdUtils.pageId(1, (byte)1, 0x7FFFFFFF);
        assertEquals(0x7FFFFFFF, PageIdUtils.pageIndex(idBigIdx));
        assertEquals(1, PageIdUtils.partId(idBigIdx));

        // partId 占 bits 32-47,大 partId 不得渗入 flag 段
        long idBigPart = PageIdUtils.pageId(PageIdUtils.MAX_PART_ID, (byte)0, 0);
        assertEquals(PageIdUtils.MAX_PART_ID, PageIdUtils.partId(idBigPart));
        assertEquals((byte)0, PageIdUtils.flag(idBigPart));
    }

    @Test
    void effectivePageIdStripsFlagAndRotation() {
        long id = PageIdUtils.pageId(7, PageIdAllocator.FLAG_IDX, 999);

        // link 把 itemId 写进高 8 位(rotation 段)
        long withLink = PageIdUtils.link(id, 100);
        assertNotEquals(id, withLink, "link 改变了高位(rotation 段)");

        long eff = PageIdUtils.effectivePageId(withLink);

        // effective 保留 pageIdx + partId(bits 0-47)
        assertEquals(PageIdUtils.pageIndex(id), PageIdUtils.pageIndex(eff));
        assertEquals(PageIdUtils.partId(id), PageIdUtils.partId(eff));

        // effective 抹掉了 flag + rotation:id 与 withLink 的 effective 相同
        assertEquals(PageIdUtils.effectivePageId(id), eff);
    }

    @Test
    void rotatePageIdNeverZero() {
        long id = PageIdUtils.pageId(3, PageIdAllocator.FLAG_DATA, 50);
        assertEquals(0, PageIdUtils.rotationId(id), "新建 pageId rotation=0");

        // 连续 rotate 超过 MAX_ITEMID_NUM(=0xFE=254),绕回 1,永不为 0
        long cur = id;
        for (int i = 0; i < PageIdUtils.MAX_ITEMID_NUM + 5; i++) {
            cur = PageIdUtils.rotatePageId(cur);
            long rot = PageIdUtils.rotationId(cur);
            assertNotEquals(0, rot, "rotation 永不为 0(第 " + i + " 次)");
            assertTrue(rot >= 1 && rot <= PageIdUtils.MAX_ITEMID_NUM,
                "rotation 落在 1.." + PageIdUtils.MAX_ITEMID_NUM + "(第 " + i + " 次,实际=" + rot + ')');
        }

        // 旋转不改变 effective 身份(pageIdx+partId 不变)
        assertEquals(PageIdUtils.effectivePageId(id), PageIdUtils.effectivePageId(cur));
        assertEquals(PageIdUtils.pageIndex(id), PageIdUtils.pageIndex(cur));
    }
}
