package org.apache.ignite.learning.internal.pagemem;

import org.apache.ignite.learning.internal.mem.unsafe.UnsafeMemoryProvider;
import org.apache.ignite.learning.internal.pagemem.impl.PageMemoryNoStoreImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DataRegion 容器 + lifecycle + config 装配(对应 S09 执行规格 §5)。
 */
class DataRegionTest {

    @Test
    void lifecycleAndConfig() {
        DataRegionConfiguration cfg = new DataRegionConfiguration()
            .setName("test-region")
            .setInitialSize(4L * 4096)
            .setMaxSize(4L * 4096)
            .setPageSize(4096);
        UnsafeMemoryProvider provider = new UnsafeMemoryProvider();
        DataRegionMetricsImpl metrics = new DataRegionMetricsImpl();

        PageMemory pm = new PageMemoryNoStoreImpl(cfg, provider, metrics);
        DataRegion region = new DataRegion(pm, cfg, metrics);

        // POJO 装配:三 getter 反指传入对象
        assertEquals("test-region", region.config().getName());
        assertSame(pm, region.pageMemory());
        assertSame(metrics, region.metrics());

        // lifecycle:外部驱动 start/stop
        region.pageMemory().start();
        long id = region.pageMemory().allocatePage(0, 1, PageIdAllocator.FLAG_DATA);
        assertTrue(id != 0);
        assertEquals(1, region.metrics().totalPages().sum(), "metrics.totalPages 跟踪分配");

        region.pageMemory().stop();
    }
}
