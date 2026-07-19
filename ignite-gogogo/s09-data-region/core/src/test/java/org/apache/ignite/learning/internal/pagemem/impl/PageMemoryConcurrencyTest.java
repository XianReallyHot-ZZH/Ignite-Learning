package org.apache.ignite.learning.internal.pagemem.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.ignite.learning.internal.pagemem.PageIdAllocator;
import org.apache.ignite.learning.internal.pagemem.PageMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多线程并发 alloc/free:验证无丢失 / 无重复(ABA 正确 + free-list + 段分配线程安全)(对应 S09 §5)。
 */
class PageMemoryConcurrencyTest {
    private static final int PAGE_SIZE = 4096;

    private PageMemory mem;

    @AfterEach
    void tearDown() {
        if (mem != null)
            mem.stop();
    }

    @Test
    void noLostOrDuplicate() throws Exception {
        int totalPages = 64;
        mem = new PageMemoryNoStoreImpl(PAGE_SIZE, (long)PAGE_SIZE * totalPages);
        mem.start();

        int threads = 4;
        int iters = 3000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                List<Long> held = new ArrayList<>();
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < iters; i++) {
                    if (held.size() < 8 && rnd.nextBoolean())
                        held.add(mem.allocatePage(0, 1, PageIdAllocator.FLAG_DATA));
                    else if (!held.isEmpty())
                        mem.freePage(0, held.remove(rnd.nextInt(held.size())));
                }
                for (long id : held)
                    mem.freePage(0, id);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures)
            f.get(); // 任何线程抛异常(含 ABA 导致的超分配/OOM)→ 测试失败
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // 无超分配(ABA/锁 bug 会导致 loadedPages > totalPages)
        assertTrue(mem.loadedPages() <= totalPages,
            "loadedPages=" + mem.loadedPages() + " 应 <= " + totalPages);
        // 全部释放后 free-list 回收 → loadedPages 归零
        assertEquals(0, mem.loadedPages(), "全部释放后 loadedPages 应为 0");
    }
}
