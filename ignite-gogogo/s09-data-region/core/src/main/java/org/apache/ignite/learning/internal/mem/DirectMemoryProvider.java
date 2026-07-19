package org.apache.ignite.learning.internal.mem;

/**
 * 堆外内存 provider 契约(镜像 Ignite {@code internal/mem/DirectMemoryProvider})。
 * 惰性产出堆外 chunk:先 {@link #initialize(long[])} 声明各段大小,再逐次 {@link #nextRegion()} 取。
 */
public interface DirectMemoryProvider {
    /** 声明各段 chunk 大小(字节);后续 nextRegion 按序产出。 */
    void initialize(long[] chunkSizes);

    /** 取下一块堆外 region;无更多 chunk 返回 {@code null}。 */
    DirectMemoryRegion nextRegion();

    /** 关闭:deallocate=true 释放所有已分配 region。 */
    void shutdown(boolean deallocate);
}
