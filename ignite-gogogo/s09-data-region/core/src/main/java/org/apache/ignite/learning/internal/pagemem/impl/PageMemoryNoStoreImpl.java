package org.apache.ignite.learning.internal.pagemem.impl;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ignite.learning.internal.mem.DirectMemoryProvider;
import org.apache.ignite.learning.internal.mem.DirectMemoryRegion;
import org.apache.ignite.learning.internal.mem.unsafe.UnsafeMemoryProvider;
import org.apache.ignite.learning.internal.pagemem.DataRegionConfiguration;
import org.apache.ignite.learning.internal.pagemem.DataRegionMetricsImpl;
import org.apache.ignite.learning.internal.pagemem.PageIdUtils;
import org.apache.ignite.learning.internal.pagemem.PageMemory;
import org.apache.ignite.learning.internal.util.OffheapReadWriteLock;

/**
 * {@link PageMemory} 的纯内存实现 v2(镜像 Ignite {@code internal/pagemem/impl/PageMemoryNoStoreImpl})。
 *
 * <p><b>S9 v2 升级</b>(在 S8 单段基础上加):
 * <ul>
 *   <li><b>全局 Treiber free-list</b>(页回收复用):{@code AtomicLong freePageListHead}(低 56 位 relative
 *       pointer + 高 8 位 ABA 计数器);侵入式 next(被释放页 offset 0 前 8 字节,复用 {@code PAGE_MARKER} 槽);
 *       {@code borrowFreePage}(CAS pop)/{@code releaseFreePage}(CAS push)。</li>
 *   <li><b>多段惰性增长</b>:{@code Segment[]},pageIdx 高 {@code SEG_BITS=4} 位编码段号,free-list relPtr 跨段;
 *       末段满 {@code addSegment}(synchronized + DCL)拉下一段。</li>
 *   <li><b>条带 {@link OffheapReadWriteLock}</b>:页内 8B 锁字 + tag 防陈旧 + read/write/upgrade。替换 S8 的
 *       {@code synchronized} 粗锁占位。</li>
 *   <li><b>{@link DirectMemoryProvider}</b>:惰性产出堆外 chunk(经 {@link UnsafeMemoryProvider} →
 *       {@code OffHeap.allocateMemory})。</li>
 * </ul>
 *
 * <p><b>页头 24B 布局</b>(与 S8 对齐):offset 0-7 {@code PAGE_MARKER} / 8-15 {@code pageId} /
 * 16-23 锁字({@code OffheapReadWriteLock})。
 *
 * <p><b>简化(标 deferred)</b>:① Segment 用 {@code AtomicInteger} bump(Ignite 用堆外 {@code lastAllocatedIdxPtr}
 * CAS,学习版简化为 Java CAS,功能等价且不占堆外 8B);② chunks 仅两段(Ignite {@code SEG_CNT=16} 分段);
 * ③ 锁不做公平调度/随机策略;④ 无页驱逐。
 *
 * <p><b>不变量</b>:{@code allocatePage} 先 pop free-list(回收复用)、空则末段 bump、满则 {@code addSegment};
 * free-list 的 ABA 计数器每次 pop 单调 +1。{@code releasePage} 近 no-op(无 per-page refcount,镜像 Ignite)。
 */
public class PageMemoryNoStoreImpl implements PageMemory {
    public static final long PAGE_MARKER = 0xBEEAAFDEADBEEF01L;

    public static final int PAGE_ID_OFFSET = 8;

    public static final int LOCK_OFFSET = 16;

    public static final int LOCK_SIZE = 8;

    public static final int PAGE_OVERHEAD = LOCK_OFFSET + LOCK_SIZE; // 24

    // ---- free-list 常量(镜像 Ignite)----
    /** relative pointer 掩码(低 56 位)。 */
    private static final long ADDRESS_MASK = 0xFFFFFFFFFFFFFFL;

    /** ABA 计数器掩码(高 8 位)。 */
    private static final long COUNTER_MASK = ~ADDRESS_MASK;

    /** ABA 计数器增量。 */
    private static final long COUNTER_INC = ADDRESS_MASK + 1;

    /** 空哨兵(free-list 为空时 head 的 relPtr)。 */
    private static final long INVALID_REL_PTR = ADDRESS_MASK;

    /** 段分配满的 pageIdx 哨兵。 */
    private static final int INVALID_PAGE_IDX = -1;

    // ---- 多段常量(镜像 Ignite)----
    private static final int SEG_BITS = 4;

    private static final int SEG_CNT = 1 << SEG_BITS; // 16

    private static final int IDX_BITS = PageIdUtils.PAGE_IDX_SIZE - SEG_BITS; // 28

    private static final int SEG_MASK = SEG_CNT - 1;

    private static final int IDX_MASK = ~(-1 << IDX_BITS);

    // ---- 实例字段----
    private final int sysPageSize;

    private final DirectMemoryProvider directMemoryProvider;

    private final DataRegionConfiguration dataRegionCfg;

    private final DataRegionMetricsImpl dataRegionMetrics;

    private final OffheapReadWriteLock rwLock;

    /** 全局 free-list 头:低 56 位 relPtr + 高 8 位 ABA 计数器。 */
    private final AtomicLong freePageListHead = new AtomicLong(INVALID_REL_PTR);

    private final AtomicInteger allocatedPages = new AtomicInteger();

    private volatile Segment[] segments;

    private final Object segmentsLock = new Object();

    private volatile boolean started;

    /**
     * S8 兼容构造(单段,initialSize=maxSize=regionSize)。供 S8 继承测试复用。
     *
     * @param pageSize   页大小(字节)
     * @param regionSize 堆外内存总大小(字节)
     */
    public PageMemoryNoStoreImpl(int pageSize, long regionSize) {
        this(new DataRegionConfiguration()
                .setPageSize(pageSize)
                .setInitialSize(regionSize)
                .setMaxSize(regionSize),
            new UnsafeMemoryProvider(),
            new DataRegionMetricsImpl());
    }

    /**
     * v2 构造(多段惰性 + provider 抽象)。
     *
     * @param dataRegionCfg       配置(pageSize / initialSize / maxSize)
     * @param directMemoryProvider 堆外 chunk provider
     * @param dataRegionMetrics    metrics(可为 null,内部建)
     */
    public PageMemoryNoStoreImpl(
        DataRegionConfiguration dataRegionCfg,
        DirectMemoryProvider directMemoryProvider,
        DataRegionMetricsImpl dataRegionMetrics
    ) {
        this.sysPageSize = dataRegionCfg.getPageSize();
        this.dataRegionCfg = dataRegionCfg;
        this.directMemoryProvider = directMemoryProvider;
        this.dataRegionMetrics = dataRegionMetrics != null ? dataRegionMetrics : new DataRegionMetricsImpl();
        // 条带数 = 4(Ignite 用 nearestPow2(4*cores);学习版固定 4,测试足够)。
        this.rwLock = new OffheapReadWriteLock(4);
    }

    @Override public synchronized void start() {
        if (started)
            return;
        long[] chunks = computeChunks(dataRegionCfg.getInitialSize(), dataRegionCfg.getMaxSize());
        directMemoryProvider.initialize(chunks);
        addSegment(null); // 拉首段
        started = true;
    }

    /** 简化:首段 = initialSize,余量 = maxSize-initialSize 作第二段(Ignite 按 SEG_CNT=16 分段)。 */
    private long[] computeChunks(long initialSize, long maxSize) {
        if (maxSize <= initialSize)
            return new long[] {initialSize};
        return new long[] {initialSize, maxSize - initialSize};
    }

    @Override public synchronized void stop() {
        if (!started)
            return;
        started = false;
        directMemoryProvider.shutdown(true);
        segments = null;
        freePageListHead.set(INVALID_REL_PTR);
        allocatedPages.set(0);
    }

    @Override public int pageSize() {
        return sysPageSize;
    }

    @Override public int systemPageSize() {
        return sysPageSize;
    }

    @Override public long loadedPages() {
        return allocatedPages.get();
    }

    /** 测试钩子:全局 free-list head 当前值(低 56 位 relPtr + 高 8 位 ABA 计数器)。 */
    long freeListHeadValue() {
        return freePageListHead.get();
    }

    // ---- 分配 ----

    @Override public long allocatePage(int grpId, int partId, byte flags) {
        if (!started)
            throw new IllegalStateException("PageMemory 未 start");

        long relPtr = borrowFreePage(grpId);
        final int pageIdx;
        final long absPtr;
        if (relPtr != INVALID_REL_PTR) {
            // 复用 free-list 页(borrowFreePage 内已 allocatedPages++)
            pageIdx = PageIdUtils.pageIndex(relPtr);
            absPtr = segment(pageIdx).absolute(pageIdx);
        } else {
            // 末段 bump;满则 addSegment 拉新段。
            // 乐观读 segments(末段);若 addSegment 期间别的线程已加段,DCL 会返回最新末段,本循环用新 segs 重试。
            while (true) {
                Segment[] segs = segments;
                Segment seg = segs[segs.length - 1];
                int idx = seg.allocateFreePage();
                if (idx != INVALID_PAGE_IDX) {
                    pageIdx = idx;
                    absPtr = seg.absolute(pageIdx);
                    break;
                }
                Segment added = addSegment(segs);
                if (added == null)
                    throw new PageMemoryOutOfMemoryException(
                        "页内存耗尽:maxSize 用尽且 free-list 空(已分配 " + allocatedPages.get() + " 页)");
            }
            allocatedPages.incrementAndGet();
            dataRegionMetrics.totalPages().increment();
        }

        long pageId = PageIdUtils.pageId(partId, flags, pageIdx);
        OffHeap.putLong(absPtr, PAGE_MARKER); // offset 0:in-use 标记(free-list next 槽复用,故分配时重写)
        OffHeap.putLong(absPtr + PAGE_ID_OFFSET, pageId); // 写头 pageId
        rwLock.init(absPtr + LOCK_OFFSET, PageIdUtils.tag(pageId)); // 重置锁字(LOCK_CNT=0, TAG=本页 tag)
        OffHeap.zeroMemory(absPtr + PAGE_OVERHEAD, sysPageSize - PAGE_OVERHEAD); // 清零数据区
        return pageId;
    }

    /** 从全局 free-list pop 一页(CAS + ABA 计数器)。返回 relPtr 或 {@code INVALID_REL_PTR}(空)。 */
    private long borrowFreePage(int grpId) {
        while (true) {
            long head = freePageListHead.get();
            long relPtr = head & ADDRESS_MASK;
            if (relPtr == INVALID_REL_PTR)
                return INVALID_REL_PTR;

            int pageIdx = PageIdUtils.pageIndex(relPtr);
            long absPtr = segment(pageIdx).absolute(pageIdx);
            long nextRelPtr = OffHeap.getLong(absPtr) & ADDRESS_MASK; // next 指针就在该页 offset 0
            long cnt = ((head & COUNTER_MASK) + COUNTER_INC) & COUNTER_MASK; // ABA 计数器 +1

            if (freePageListHead.compareAndSet(head, nextRelPtr | cnt)) {
                OffHeap.putLong(absPtr, PAGE_MARKER); // 标 in-use(覆盖 next 槽)
                allocatedPages.incrementAndGet();
                dataRegionMetrics.totalPages().increment();
                return relPtr;
            }
        }
    }

    @Override public void freePage(int grpId, long pageId) {
        if (!started)
            throw new IllegalStateException("PageMemory 未 start");
        releaseFreePage(grpId, pageId);
    }

    /** 把页 push 回全局 free-list(CAS 入栈,next 写到页 offset 0)。 */
    private void releaseFreePage(int grpId, long pageId) {
        int pageIdx = PageIdUtils.pageIndex(pageId);
        long relPtr = PageIdUtils.pageId(0, (byte)0, pageIdx); // 清 flag/tag 的干净 relPtr
        long absPtr = segment(pageIdx).absolute(pageIdx);
        OffHeap.putLong(absPtr + PAGE_ID_OFFSET, relPtr); // offset 8 写干净 relPtr

        while (true) {
            long head = freePageListHead.get();
            long freeRelPtr = head & ADDRESS_MASK;
            OffHeap.putLong(absPtr, freeRelPtr); // offset 0 写旧 head = next 指针(侵入式)
            if (freePageListHead.compareAndSet(head, relPtr)) { // 本页成新 head
                allocatedPages.decrementAndGet();
                dataRegionMetrics.totalPages().decrement();
                return;
            }
        }
    }

    // ---- acquire / 锁 ----

    @Override public long acquirePage(int grpId, long pageId) {
        int pageIdx = PageIdUtils.pageIndex(pageId);
        return segment(pageIdx).absolute(pageIdx); // 裸页头指针
    }

    @Override public void releasePage(int grpId, long pageId, long page) {
        // v1/v2 近 no-op(镜像 Ignite trackAcquiredPages=false);回收走 freePage。
    }

    @Override public long readLock(int grpId, long pageId, long page) {
        int tag = PageIdUtils.tag(pageId);
        return rwLock.readLock(page + LOCK_OFFSET, tag) ? page + PAGE_OVERHEAD : 0L; // 陈旧返回 0L
    }

    @Override public void readUnlock(int grpId, long pageId, long page) {
        rwLock.readUnlock(page + LOCK_OFFSET);
    }

    @Override public long writeLock(int grpId, long pageId, long page) {
        int tag = PageIdUtils.tag(pageId);
        return rwLock.writeLock(page + LOCK_OFFSET, tag) ? page + PAGE_OVERHEAD : 0L;
    }

    @Override public boolean tryWriteLock(int grpId, long pageId, long page) {
        int tag = PageIdUtils.tag(pageId);
        return rwLock.tryWriteLock(page + LOCK_OFFSET, tag);
    }

    @Override public void writeUnlock(int grpId, long pageId, long page, boolean dirty) {
        int tag = PageIdUtils.tag(pageId);
        rwLock.writeUnlock(page + LOCK_OFFSET, tag);
    }

    @Override public ByteBuffer pageBuffer(long page) {
        return OffHeap.wrapPointer(page, sysPageSize); // 整页(含 24B 头)
    }

    // ---- 多段 ----

    /** 段分配(synchronized + DCL);返回新增段或 null(无更多内存)。 */
    private synchronized Segment addSegment(Segment[] oldRef) {
        if (segments != oldRef)
            return segments == null ? null : segments[segments.length - 1]; // 别人已加,返回末段

        DirectMemoryRegion region = directMemoryProvider.nextRegion();
        if (region == null)
            return null; // 无更多 chunk

        int pagesInPrev = 0;
        Segment[] old = segments;
        if (old != null)
            for (Segment s : old)
                pagesInPrev += s.maxPages;

        Segment seg = new Segment(old == null ? 0 : old.length, region, pagesInPrev);
        seg.init();

        Segment[] newRef;
        if (old == null) {
            newRef = new Segment[] {seg};
        } else {
            newRef = new Segment[old.length + 1];
            System.arraycopy(old, 0, newRef, 0, old.length);
            newRef[old.length] = seg;
        }
        segments = newRef; // volatile 写,发布给其他线程
        return seg;
    }

    /** 由 pageIdx 高 SEG_BITS 位解码段号。 */
    private Segment segment(int pageIdx) {
        return segments[(pageIdx >> IDX_BITS) & SEG_MASK];
    }

    /** 把 (segIdx, inSegIdx) 编码成 pageIdx(高 SEG_BITS 位 = segIdx)。 */
    private static int fromSegmentIndex(int segIdx, int inSegIdx) {
        return (segIdx << IDX_BITS) | (inSegIdx & IDX_MASK);
    }

    /**
     * 一段堆外内存,按指针算术切成等大页;{@code AtomicInteger} bump 分配器。
     * (Ignite 用堆外 {@code lastAllocatedIdxPtr} CAS;学习版用 Java AtomicInteger 简化。)
     */
    private final class Segment {
        final int idx;

        final DirectMemoryRegion region;

        /** 段内 bump 指针(下一个待分配页号)。 */
        final AtomicInteger lastAllocatedIdx = new AtomicInteger(0);

        long pagesBase;

        int maxPages;

        Segment(int idx, DirectMemoryRegion region, int pagesInPrevSegments) {
            this.idx = idx;
            this.region = region;
        }

        void init() {
            pagesBase = region.address();
            maxPages = (int)(region.size() / sysPageSize);
        }

        /** 段内绝对地址:pagesBase + (pageIdx & IDX_MASK) * sysPageSize。 */
        long absolute(int pageIdx) {
            pageIdx &= IDX_MASK;
            return pagesBase + ((long)pageIdx) * sysPageSize;
        }

        /** CAS bump 分配;满返回 {@code INVALID_PAGE_IDX}。 */
        int allocateFreePage() {
            while (true) {
                int lastIdx = lastAllocatedIdx.get();
                if (lastIdx >= maxPages)
                    return INVALID_PAGE_IDX;
                if (lastAllocatedIdx.compareAndSet(lastIdx, lastIdx + 1))
                    return fromSegmentIndex(idx, lastIdx);
            }
        }
    }
}
