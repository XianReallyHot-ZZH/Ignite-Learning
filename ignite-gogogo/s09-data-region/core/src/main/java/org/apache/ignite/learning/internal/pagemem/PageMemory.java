package org.apache.ignite.learning.internal.pagemem;

import java.nio.ByteBuffer;

/**
 * 顶层页内存接口(镜像 Ignite {@code internal/pagemem/PageMemory}),{@code extends}
 * {@link PageIdAllocator} + {@link PageSupport},加生命周期 / 尺寸 / pageBuffer。
 *
 * <p>学习版<b>裁掉</b> Ignite 的 {@code metrics()} 上漏(返回类型 {@code DataRegionMetricsImpl}
 * 会把 cache 子系统依赖漏进来;metrics 留给 S9 自定)。
 */
public interface PageMemory extends PageIdAllocator, PageSupport {
    /** 启动:分配堆外内存、切页、写页头。 */
    void start();

    /** 停止:释放堆外内存。 */
    void stop();

    /** 逻辑页大小(字节)。 */
    int pageSize();

    /** OS / 系统页大小(字节)。 */
    int systemPageSize();

    /**
     * 把裸页指针包成 {@link ByteBuffer}(覆盖整页,含页头)。
     *
     * @param page 裸页头指针
     * @return 覆盖整页的 direct ByteBuffer
     */
    ByteBuffer pageBuffer(long page);

    /** 已分配(loaded)页数。 */
    long loadedPages();
}
