package org.apache.ignite.learning.internal.util.nio;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接收背压测试:{@link MessageTracker} 在途达上限时调 pauseReads,处理完(低于上限)调 resumeReads。
 * 用伪 {@link MessageTracker.PauseResume} 回调记录 pause/resume 次数(不依赖真实 NioSession/worker)。
 */
class ReceiveBackpressureTest {

    /**
     * limit=2:收到第 1 条(在途 1)不暂停;收到第 2 条(在途 2 ≥ limit)暂停;
     * 处理完 1 条(在途 1 < limit)恢复。
     */
    @Test
    void pausesAndResumesAtLimit() {
        AtomicInteger pauses = new AtomicInteger();
        AtomicInteger resumes = new AtomicInteger();
        // 伪回调:记录 pause/resume 调用次数
        MessageTracker.PauseResume ctl = new MessageTracker.PauseResume() {
            @Override public void pauseReads() {
                pauses.incrementAndGet();
            }

            @Override public void resumeReads() {
                resumes.incrementAndGet();
            }
        };

        MessageTracker t = new MessageTracker(ctl, 2);
        t.onReceived(); // 在途 1
        assertFalse(t.isPaused());
        t.onReceived(); // 在途 2 ≥ limit → 暂停
        assertTrue(t.isPaused());
        assertEquals(1, pauses.get());
        t.onProcessed(); // 在途 1 < limit → 恢复
        assertFalse(t.isPaused());
        assertEquals(1, resumes.get());
    }
}
