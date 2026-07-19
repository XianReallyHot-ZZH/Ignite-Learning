package org.apache.ignite.learning.internal.pagemem;

/**
 * 页 acquire/release + 读/写锁契约(镜像 Ignite {@code internal/pagemem/PageSupport})。
 *
 * <p>全部以<b>裸 {@code long page} 指针</b>为参(无 {@code Page} 对象);锁失败返回 {@code 0L}
 * (调用方据此重读,表示"缓存的页已陈旧")。锁的完整实现(条带 + 页内 8B 锁字 + tag 防陈旧)在
 * S9 的 {@code OffheapReadWriteLock};S8 用粗粒度 {@code synchronized} 占位。
 */
public interface PageSupport {
    /**
     * 定位/获取一页,返回裸页头指针。
     *
     * @return 裸 {@code long} 页指针(指向页头 offset 0)
     */
    long acquirePage(int grpId, long pageId);

    /** 释放一页的引用(v1 近 no-op)。 */
    void releasePage(int grpId, long pageId, long page);

    /**
     * 读锁。成功返回数据区指针(page + {@code PAGE_OVERHEAD});失败返回 {@code 0L}。
     */
    long readLock(int grpId, long pageId, long page);

    /** 解读锁。 */
    void readUnlock(int grpId, long pageId, long page);

    /**
     * 写锁(独占)。成功返回数据区指针;失败(被占)阻塞或返回 {@code 0L}(看实现)。
     */
    long writeLock(int grpId, long pageId, long page);

    /** 尝试写锁,不阻塞;成功 true。 */
    boolean tryWriteLock(int grpId, long pageId, long page);

    /** 解写锁;{@code dirty} 标脏(下游 checkpoint/WAL 用,S8 仅占位)。 */
    void writeUnlock(int grpId, long pageId, long page, boolean dirty);
}
