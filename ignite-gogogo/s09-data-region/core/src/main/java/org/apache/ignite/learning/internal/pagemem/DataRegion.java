package org.apache.ignite.learning.internal.pagemem;

/**
 * DataRegion 容器(镜像 Ignite {@code internal/processors/cache/persistence/DataRegion})。
 *
 * <p>~POJO:装配 {@link PageMemory} + {@link DataRegionConfiguration} + {@link DataRegionMetricsImpl};
 * <b>自身无生命周期代码</b>——外部(学习版的测试 / Ignite 的 {@code IgniteCacheDatabaseSharedManager})
 * 调 {@code pageMemory().start()} / {@code stop()} 驱动。
 *
 * <p>学习版去掉 Ignite 的 {@code PageEvictionTracker}(驱逐,deferred)字段。
 */
public final class DataRegion {
    private final PageMemory pageMemory;

    private final DataRegionConfiguration config;

    private final DataRegionMetricsImpl metrics;

    public DataRegion(PageMemory pageMemory, DataRegionConfiguration config, DataRegionMetricsImpl metrics) {
        this.pageMemory = pageMemory;
        this.config = config;
        this.metrics = metrics;
    }

    public PageMemory pageMemory() {
        return pageMemory;
    }

    public DataRegionConfiguration config() {
        return config;
    }

    public DataRegionMetricsImpl metrics() {
        return metrics;
    }
}
