package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 发送背压测试:有界写队列({@link BoundedWriteQueue})满时,非 message-thread 的 offer 应阻塞;
 * 排空(释放 permit)后阻塞解除。
 *
 * <p>关键:测试线程不是 message-thread(无 {@link GridBackPressureControl} 标记),
 * 所以 offer 会真正获取信号量、在满时阻塞。</p>
 */
class SendBackpressureTest {

    /**
     * 容量 1:先 offer 一条占满 → 另一线程 offer 第二条应阻塞 → poll 排空释放 permit → 第二条解除阻塞入队。
     */
    @Test
    void blocksWhenFullThenDrains() throws Exception {
        BoundedWriteQueue q = new BoundedWriteQueue(1); // 容量 1
        q.offer(ByteBuffer.allocate(4)); // 占满(permit 1→0)

        ExecutorService pool = Executors.newSingleThreadExecutor();
        // 另一线程(非 message-thread)offer 第二条 → 应阻塞在 acquire
        Future<?> f = pool.submit(() -> q.offer(ByteBuffer.allocate(4)));

        // 100ms 后仍未完成 → 确认它阻塞了(而非立即返回)
        try {
            f.get(100, TimeUnit.MILLISECONDS);
            fail("第二个 offer 应阻塞");
        } catch (TimeoutException ok) {
            // 预期:阻塞中
        }
        assertFalse(f.isDone());

        // 排空一条 → 释放 permit → 阻塞的 offer 解除
        assertNotNull(q.poll());
        f.get(3, TimeUnit.SECONDS); // 现在应完成
        assertTrue(f.isDone());
        assertNotNull(q.peek()); // 第二条已入队

        pool.shutdownNow();
    }
}
