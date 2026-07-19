package org.apache.ignite.learning.internal.pagemem.impl;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ignite.learning.internal.pagemem.PageIdUtils;
import org.apache.ignite.learning.internal.pagemem.PageMemory;

/**
 * {@link PageMemory} 的纯内存实现(镜像 Ignite {@code internal/pagemem/impl/PageMemoryNoStoreImpl}),
 * <b>S8 裁剪版</b>:单段堆外内存 + bump 分配 + 粗粒度 {@code synchronized} 锁占位。
 *
 * <p>S8 <b>不做</b>(留 S9):
 * <ul>
 *   <li>全局 Treiber free-list(页回收复用:侵入式 next + ABA 计数器);</li>
 *   <li>多段惰性增长(SEG_CNT=16,addSegment);</li>
 *   <li>条带 {@code OffheapReadWriteLock}(页内 8B 锁字 + tag 防陈旧)。</li>
 * </ul>
 *
 * <p>页头 24B 布局(对齐 Ignite):
 * <pre>
 *  offset  0-7   PAGE_MARKER (8B,in-use 标记 / S9 free-list next 槽)
 *  offset  8-15  pageId (PAGE_ID_OFFSET=8)
 *  offset 16-23  lock 占位 (LOCK_OFFSET=16;S9 才填 8B 锁字,LOCK_SIZE=8)
 *  PAGE_OVERHEAD = LOCK_OFFSET + LOCK_SIZE = 24
 * </pre>
 *
 * <p><b>不变量</b>:S8 的 {@code allocatePage} 单调 bump({@code nextIdx} 递增,不回收);
 * 段满抛 {@link PageMemoryOutOfMemoryException}(无 free-list)。
 */
public class PageMemoryNoStoreImpl implements PageMemory {
    /** 页头 in-use 标记(镜像 Ignite {@code PAGE_MARKER})。 */
    public static final long PAGE_MARKER = 0xBEEAAFDEADBEEF01L;

    /** pageId 在页头的偏移(镜像 Ignite {@code PAGE_ID_OFFSET})。 */
    public static final int PAGE_ID_OFFSET = 8;

    /** 锁字在页头的偏移(镜像 Ignite {@code LOCK_OFFSET})。 */
    public static final int LOCK_OFFSET = 16;

    /** 锁字大小(镜像 Ignite {@code OffheapReadWriteLock.LOCK_SIZE}=8)。 */
    public static final int LOCK_SIZE = 8;

    /** 页头开销(镜像 Ignite {@code PAGE_OVERHEAD}=24)。 */
    public static final int PAGE_OVERHEAD = LOCK_OFFSET + LOCK_SIZE;

    private final int pageSize;
    private final long regionSize;
    private final int totalPages;

    private long pagesBase;

    private final AtomicInteger nextIdx = new AtomicInteger(0);

    private volatile boolean started;

    /**
     * @param pageSize   页大小(字节)。S8 简化:逻辑页 == 系统页(pageSize == systemPageSize);
     *                   Ignite 中二者可不同(逻辑页须为系统页倍数)。
     * @param regionSize 堆外内存总大小(字节);切为 {@code regionSize/pageSize} 页。
     */
    public PageMemoryNoStoreImpl(int pageSize, long regionSize) {
        if (pageSize <= PAGE_OVERHEAD)
            throw new IllegalArgumentException("pageSize 须 > PAGE_OVERHEAD=" + PAGE_OVERHEAD + ",实际=" + pageSize);
        this.pageSize = pageSize;
        this.regionSize = regionSize;
        this.totalPages = (int)(regionSize / pageSize);
        if (totalPages <= 0)
            throw new IllegalArgumentException("regionSize/pageSize 须 >= 1:regionSize=" + regionSize + ",pageSize=" + pageSize);
    }

    @Override public synchronized void start() {
        if (started)
            return;
        this.pagesBase = OffHeap.allocateMemory(regionSize);
        for (int i = 0; i < totalPages; i++)
            OffHeap.putLong(pagesBase + ((long)i) * pageSize, PAGE_MARKER);
        started = true;
    }

    @Override public synchronized void stop() {
        if (!started)
            return;
        OffHeap.freeMemory(pagesBase);
        started = false;
    }

    @Override public int pageSize() {
        return pageSize;
    }

    @Override public int systemPageSize() {
        return pageSize;
    }

    @Override public long loadedPages() {
        return nextIdx.get();
    }

    @Override public synchronized long allocatePage(int grpId, int partId, byte flags) {
        if (!started)
            throw new IllegalStateException("PageMemory 未 start");
        int idx = nextIdx.getAndIncrement();
        if (idx >= totalPages) {
            nextIdx.decrementAndGet(); // 回退,保持 loadedPages 反映真实已分配数
            throw new PageMemoryOutOfMemoryException(
                "页内存段满:已分配 " + totalPages + " 页 / " + regionSize + "B,S8 无 free-list(回收复用见 S9)");
        }
        long absPtr = absolute(idx);
        long pageId = PageIdUtils.pageId(partId, flags, idx);
        OffHeap.putLong(absPtr + PAGE_ID_OFFSET, pageId); // 写头 pageId
        OffHeap.zeroMemory(absPtr + PAGE_OVERHEAD, pageSize - PAGE_OVERHEAD); // 清零数据区(镜像 Ignite)
        return pageId;
    }

    /**
     * 定位一页,返回裸页头指针(指向 offset 0)。由 pageId 的 pageIdx 经指针算术定位;不做加锁 /
     * 引用计数(S8 v1,镜像 Ignite {@code acquirePage} 仅返回指针)。调用方拿指针后用
     * {@link #pageBuffer(long)} 包成 ByteBuffer 读写,或经 {@link #readLock}/{@link #writeLock} 占位。
     *
     * @param grpId  cache group id(S8 单 region 未使用;Ignite 多 cache group 时用于区分)
     * @param pageId 页 id
     * @return 裸 {@code long} 页头指针
     */
    @Override public long acquirePage(int grpId, long pageId) {
        return absolute(PageIdUtils.pageIndex(pageId));
    }

    @Override public void releasePage(int grpId, long pageId, long page) {
        // v1 no-op(镜像 Ignite trackAcquiredPages=false);S9 回收走 freePage。
    }

    // S8 锁粗粒度占位:方法级 synchronized,直接返回数据区指针(page + PAGE_OVERHEAD)。
    // 锁失败约定返回 0L —— S8 粗锁下恒成功;S9 才上页内 8B 锁字 + tag(返回 0L 检出陈旧页)。
    @Override public synchronized long readLock(int grpId, long pageId, long page) {
        return page + PAGE_OVERHEAD;
    }

    @Override public synchronized void readUnlock(int grpId, long pageId, long page) {
        // No-op(S8 粗锁随方法退出释放)。
    }

    @Override public synchronized long writeLock(int grpId, long pageId, long page) {
        return page + PAGE_OVERHEAD;
    }

    @Override public synchronized boolean tryWriteLock(int grpId, long pageId, long page) {
        return true;
    }

    @Override public synchronized void writeUnlock(int grpId, long pageId, long page, boolean dirty) {
        // No-op(dirty 标脏由 S15 checkpoint/WAL 用;S8 仅占位)。
    }

    @Override public ByteBuffer pageBuffer(long page) {
        return OffHeap.wrapPointer(page, pageSize); // 整页(含 24B 头),对齐 Ignite wrapPointer(pageAddr, pageSize)
    }

    @Override public void freePage(int grpId, long pageId) {
        // S8 无 free-list;freePage 为 no-op(页不回收)。S9 才实现 Treiber 入栈。
    }

    /** 页头基地址算术:pagesBase + pageIdx * pageSize(镜像 Ignite Segment.absolute)。 */
    private long absolute(int pageIdx) {
        return pagesBase + ((long)pageIdx) * pageSize;
    }
}
