package org.apache.ignite.learning.internal.mem.unsafe;

import java.util.ArrayDeque;
import org.apache.ignite.learning.internal.mem.DirectMemoryProvider;
import org.apache.ignite.learning.internal.mem.DirectMemoryRegion;
import org.apache.ignite.learning.internal.pagemem.impl.OffHeap;

/**
 * 默认 provider(镜像 Ignite {@code internal/mem/unsafe/UnsafeMemoryProvider}):每次 {@link #nextRegion()}
 * 经 {@link OffHeap#allocateMemory(long)} 拿一块堆外内存(底层 {@code sun.misc.Unsafe.allocateMemory})。
 * 记录所有已分配 region,{@link #shutdown(boolean)} 时统一释放。
 */
public class UnsafeMemoryProvider implements DirectMemoryProvider {
    private long[] chunkSizes;

    private int nextIdx;

    private final ArrayDeque<DirectMemoryRegion> allocated = new ArrayDeque<>();

    @Override public void initialize(long[] chunkSizes) {
        this.chunkSizes = chunkSizes;
        this.nextIdx = 0;
    }

    @Override public DirectMemoryRegion nextRegion() {
        if (chunkSizes == null || nextIdx >= chunkSizes.length)
            return null;
        long size = chunkSizes[nextIdx++];
        long addr = OffHeap.allocateMemory(size);
        OffHeap.zeroMemory(addr, size); // 清零(Ignite 同款)
        DirectMemoryRegion region = new DirectMemoryRegion(addr, size);
        allocated.add(region);
        return region;
    }

    @Override public void shutdown(boolean deallocate) {
        if (deallocate) {
            for (DirectMemoryRegion r : allocated)
                OffHeap.freeMemory(r.address());
        }
        allocated.clear();
        chunkSizes = null;
        nextIdx = 0;
    }
}
