package org.apache.ignite.learning.internal.pagemem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link FullPageId} rotation-blind 相等性 + NULL_PAGE 哨兵单测(对应 S08 执行规格 §5)。
 */
class FullPageIdTest {

    @Test
    void rotationBlindEquality() {
        long base = PageIdUtils.pageId(5, PageIdAllocator.FLAG_DATA, 100);
        FullPageId a = new FullPageId(base, 1);
        FullPageId b = new FullPageId(base, 1);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        // rotation 不同(rotatePageId 改高 8 位),effective 相同 → 仍相等
        long rotated = PageIdUtils.rotatePageId(base);
        FullPageId c = new FullPageId(rotated, 1);
        assertEquals(a.effectivePageId(), c.effectivePageId(), "rotation 不影响 effectivePageId");
        assertEquals(a, c, "rotation-blind:rotation 变不影响相等");
        assertEquals(a.hashCode(), c.hashCode());

        // effectivePageId 同样抹掉 flag(bits 48-55):flag 变也不影响相等
        long diffFlag = PageIdUtils.pageId(5, PageIdAllocator.FLAG_IDX, 100);
        assertEquals(a.effectivePageId(), new FullPageId(diffFlag, 1).effectivePageId());
        assertEquals(a, new FullPageId(diffFlag, 1), "flag-blind:effectivePageId 抹 flag 段");

        // partId 不同 → 不等
        long diffPart = PageIdUtils.pageId(6, PageIdAllocator.FLAG_DATA, 100);
        assertNotEquals(a, new FullPageId(diffPart, 1));

        // grpId 不同 → 不等
        assertNotEquals(a, new FullPageId(base, 2));
    }

    @Test
    void nullPageSentinel() {
        assertNotNull(FullPageId.NULL_PAGE);
        assertEquals(-1, FullPageId.NULL_PAGE.groupId());

        long normal = PageIdUtils.pageId(1, PageIdAllocator.FLAG_DATA, 1);
        assertNotEquals(FullPageId.NULL_PAGE, new FullPageId(normal, 1));

        assertEquals(FullPageId.NULL_PAGE, FullPageId.NULL_PAGE);
    }
}
