package org.apache.ignite.learning.internal.pagemem;

/**
 * 页 id 位运算工具(镜像 Ignite {@code internal/pagemem/PageIdUtils})。
 *
 * <p>64-bit pageId 布局(LSB→MSB):
 * <pre>
 *  bits  0-31  pageIdx   (PAGE_IDX_SIZE=32)  单 partition 内单调递增
 *  bits 32-47  partId    (PART_ID_SIZE=16)   最多 65500(MAX_PARTITION_ID)
 *  bits 48-55  flag      (FLAG_SIZE=8)       FLAG_DATA/FLAG_IDX/FLAG_AUX
 *  bits 56-63  rotation  (8 bit)            回收代 / 页内 item 偏移,1..254 绕回 1
 * </pre>
 *
 * <p>{@code effectivePageId} 抹掉 flag+rotation,只保留 pageIdx+partId(bits 0-47),
 * 供 {@link FullPageId} 做 rotation-blind equals/hashCode。
 *
 * <p><b>不变量</b>:{@link #rotatePageId(long)} 永不产生 rotation==0(与"未分配"区分)。
 */
public final class PageIdUtils {
    public static final int PAGE_IDX_SIZE = 32;
    public static final int PART_ID_SIZE = 16;
    public static final int FLAG_SIZE = 8;
    public static final int OFFSET_SIZE = 8;
    public static final int TAG_SIZE = 16;
    public static final int ROTATION_ID_OFFSET = PAGE_IDX_SIZE + PART_ID_SIZE + FLAG_SIZE; // 56

    public static final long PAGE_IDX_MASK = ~(-1L << PAGE_IDX_SIZE);
    public static final long PART_ID_MASK = ~(-1L << PART_ID_SIZE);
    public static final long FLAG_MASK = ~(-1L << FLAG_SIZE);
    public static final long OFFSET_MASK = ~(-1L << OFFSET_SIZE);
    public static final long TAG_MASK = ~(-1L << TAG_SIZE);
    public static final long EFFECTIVE_PAGE_ID_MASK = ~(-1L << (PAGE_IDX_SIZE + PART_ID_SIZE)); // bits 0-47
    public static final long PAGE_ID_MASK = ~(-1L << ROTATION_ID_OFFSET); // bits 0-55

    public static final int MAX_ITEMID_NUM = 0xFE;
    public static final long MAX_PAGE_NUM = (1L << PAGE_IDX_SIZE) - 1;
    public static final int MAX_PART_ID = (1 << PART_ID_SIZE) - 1;

    private PageIdUtils() {
        // No-op.
    }

    public static long pageId(int partId, byte flag, int pageIdx) {
        long id = flag & FLAG_MASK;
        id = (id << PART_ID_SIZE) | (partId & PART_ID_MASK);
        id = (id << PAGE_IDX_SIZE) | (pageIdx & PAGE_IDX_MASK);
        return id;
    }

    public static int pageIndex(long pageId) {
        return (int)(pageId & PAGE_IDX_MASK);
    }

    public static int partId(long pageId) {
        return (int)((pageId >>> PAGE_IDX_SIZE) & PART_ID_MASK);
    }

    public static byte flag(long pageId) {
        return (byte)((pageId >>> (PART_ID_SIZE + PAGE_IDX_SIZE)) & FLAG_MASK);
    }

    public static long effectivePageId(long link) {
        return link & EFFECTIVE_PAGE_ID_MASK;
    }

    public static long rotationId(long pageId) {
        return pageId >>> ROTATION_ID_OFFSET;
    }

    public static long rotatePageId(long pageId) {
        long updated = rotationId(pageId) + 1;
        if (updated > MAX_ITEMID_NUM)
            updated = 1; // 永不为 0
        return (pageId & PAGE_ID_MASK) | (updated << ROTATION_ID_OFFSET);
    }

    public static long link(long pageId, int itemId) {
        return pageId | (((long)itemId) << ROTATION_ID_OFFSET);
    }

    public static int itemId(long link) {
        return (int)((link >> ROTATION_ID_OFFSET) & OFFSET_MASK);
    }

    public static int tag(long link) {
        return (int)((link >> (PAGE_IDX_SIZE + PART_ID_SIZE)) & TAG_MASK);
    }
}
