package org.apache.ignite.learning.internal.pagemem;

/**
 * 页的复合键 (grpId, pageId)(镜像 Ignite {@code internal/pagemem/FullPageId})。
 *
 * <p>关键设计:equals/hashCode <b>只认 effectivePageId + grpId</b>(rotation-blind)——
 * 同一逻辑页(partId+pageIdx+grpId 相同)即使 rotation/flag 不同也判相等。这让 FullPageId
 * 可稳定用作 map key:页被回收复用后旧引用失效由 rotation 段的 tag 机制(S9 的页内锁字)处理,
 * 不影响本类的相等性判定。
 */
public final class FullPageId {
    public static final FullPageId NULL_PAGE = new FullPageId(-1L, -1);

    private final long pageId;
    private final long effectivePageId;
    private final int grpId;

    public FullPageId(long pageId, int grpId) {
        this.pageId = pageId;
        this.grpId = grpId;
        this.effectivePageId = PageIdUtils.effectivePageId(pageId);
    }

    public long pageId() {
        return pageId;
    }

    public long effectivePageId() {
        return effectivePageId;
    }

    public int groupId() {
        return grpId;
    }

    @Override public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof FullPageId))
            return false;
        FullPageId that = (FullPageId)o;
        return effectivePageId == that.effectivePageId && grpId == that.grpId;
    }

    @Override public int hashCode() {
        return (int)(mix64(effectivePageId) ^ mix32(grpId));
    }

    // Stafford variant 9(64-bit mix)—— 镜像 Ignite FullPageId.mix64,保证 hash 分布。
    private static long mix64(long z) {
        z = (z ^ (z >>> 32)) * 0x4cd6944c5cc20b6dL;
        z = (z ^ (z >>> 29)) * 0xfc12c5b19d3259e9L;
        return z ^ (z >>> 32);
    }

    // MH3 finalization(32-bit mix)—— 镜像 Ignite FullPageId.mix32。
    private static int mix32(int k) {
        k = (k ^ (k >>> 16)) * 0x85ebca6b;
        k = (k ^ (k >>> 13)) * 0xc2b2ae35;
        return k ^ (k >>> 16);
    }

    @Override public String toString() {
        return "FullPageId[pageId=0x" + Long.toHexString(pageId)
            + ", effectivePageId=0x" + Long.toHexString(effectivePageId)
            + ", grpId=" + grpId + ']';
    }
}
