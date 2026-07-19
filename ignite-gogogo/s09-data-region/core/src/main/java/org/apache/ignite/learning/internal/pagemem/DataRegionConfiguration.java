package org.apache.ignite.learning.internal.pagemem;

/**
 * DataRegion 配置 bean(镜像 Ignite {@code configuration/DataRegionConfiguration})。
 * 链式 setter: {@code new DataRegionConfiguration().setName("x").setInitialSize(...)}。
 */
public final class DataRegionConfiguration {
    /** 默认页大小(字节)。 */
    public static final int DFLT_PAGE_SIZE = 4096;

    /** 默认初始大小(字节)。 */
    public static final long DFLT_INITIAL_SIZE = 256L * 1024 * 1024;

    private String name;

    private long initialSize = DFLT_INITIAL_SIZE;

    private long maxSize = DFLT_INITIAL_SIZE;

    private int pageSize = DFLT_PAGE_SIZE;

    public DataRegionConfiguration() {
        // No-op.
    }

    public String getName() {
        return name;
    }

    public DataRegionConfiguration setName(String name) {
        this.name = name;
        return this;
    }

    public long getInitialSize() {
        return initialSize;
    }

    public DataRegionConfiguration setInitialSize(long initialSize) {
        this.initialSize = initialSize;
        return this;
    }

    public long getMaxSize() {
        return maxSize;
    }

    public DataRegionConfiguration setMaxSize(long maxSize) {
        this.maxSize = maxSize;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public DataRegionConfiguration setPageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }
}
