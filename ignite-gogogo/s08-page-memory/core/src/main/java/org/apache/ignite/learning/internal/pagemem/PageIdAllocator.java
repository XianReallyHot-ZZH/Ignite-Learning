package org.apache.ignite.learning.internal.pagemem;

/**
 * 页分配器契约(镜像 Ignite {@code internal/pagemem/PageIdAllocator})。
 * 定义页的 flag 类型常量 + allocate/free 语义。
 */
public interface PageIdAllocator {
    /** 数据页。 */
    byte FLAG_DATA = 1;

    /** 索引页。 */
    byte FLAG_IDX = 2;

    /** 辅助页(如 free-list meta)。 */
    byte FLAG_AUX = 4;

    /** partition id 上限(Ignite 限制 65500,虽 16-bit 可表 65535)。 */
    int MAX_PARTITION_ID = 65500;

    /** 保留给索引页的 partition id(partId 语义外)。 */
    int INDEX_PARTITION = 0xFFFF;

    /** meta 页 id。 */
    long META_PAGE_ID = 0;

    /**
     * 分配一页,返回新 pageId。
     *
     * @param grpId  cache group id
     * @param partId partition id
     * @param flags  页 flag({@link #FLAG_DATA}/{@link #FLAG_IDX}/{@link #FLAG_AUX})
     * @return 新分配页的 pageId
     */
    long allocatePage(int grpId, int partId, byte flags);

    /**
     * 释放一页(S8 为 no-op;S9 才入 free-list 回收复用)。
     */
    void freePage(int grpId, long pageId);
}
