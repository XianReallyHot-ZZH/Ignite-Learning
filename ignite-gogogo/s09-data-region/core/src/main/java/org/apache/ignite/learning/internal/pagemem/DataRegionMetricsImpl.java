package org.apache.ignite.learning.internal.pagemem;

import java.util.concurrent.atomic.LongAdder;

/**
 * DataRegion metrics(镜像 Ignite {@code DataRegionMetricsImpl} 的轻量子集)。
 * 学习版只保留 {@code totalPages} 计数(borrow +1 / release -1);用 {@link LongAdder}
 * (高并发友好,有 {@code increment/decrement};Ignite 的 PageMetrics 同款语义)。
 */
public final class DataRegionMetricsImpl {
    private final LongAdder totalPages = new LongAdder();

    /** 已分配页数计数器(borrow +1 / release -1)。 */
    public LongAdder totalPages() {
        return totalPages;
    }
}
