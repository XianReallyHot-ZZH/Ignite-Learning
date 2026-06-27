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

/** 发送背压:有界写队列满时 offer 阻塞,排空后恢复(用非 message-thread 线程验证信号量)。 */
class SendBackpressureTest {

    @Test
    void blocksWhenFullThenDrains() throws Exception {
        BoundedWriteQueue q = new BoundedWriteQueue(1); // 容量 1
        q.offer(ByteBuffer.allocate(4)); // 占满(permit 1→0)

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> f = pool.submit(() -> q.offer(ByteBuffer.allocate(4))); // 非 message-thread → 应阻塞

        // 100ms 后仍未完成 → 确认阻塞
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
        assertNotNull(q.peek()); // 第二条已在队列

        pool.shutdownNow();
    }
}
