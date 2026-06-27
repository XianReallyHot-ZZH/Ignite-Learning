package org.apache.ignite.learning.internal.util.nio;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 接收背压:MessageTracker 达上限 pauseReads,处理后 resumeReads(用伪 PauseResume 回调)。 */
class ReceiveBackpressureTest {

    @Test
    void pausesAndResumesAtLimit() {
        AtomicInteger pauses = new AtomicInteger();
        AtomicInteger resumes = new AtomicInteger();
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
