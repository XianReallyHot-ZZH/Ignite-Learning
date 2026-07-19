package org.apache.ignite.learning.internal.mem;

/**
 * 一块堆外内存的 (address, size) 对(镜像 Ignite {@code internal/mem/DirectMemoryRegion})。
 * {@link DirectMemoryProvider#nextRegion()} 的返回值。
 */
public final class DirectMemoryRegion {
    private final long address;

    private final long size;

    public DirectMemoryRegion(long address, long size) {
        this.address = address;
        this.size = size;
    }

    public long address() {
        return address;
    }

    public long size() {
        return size;
    }
}
