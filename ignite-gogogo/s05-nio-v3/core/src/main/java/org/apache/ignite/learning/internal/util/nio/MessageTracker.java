package org.apache.ignite.learning.internal.util.nio;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 接收侧背压(镜像 {@code GridNioMessageTracker}):统计在途未处理消息,达上限暂停读(OP_READ),处理完恢复。
 * {@code limit <= 0} = 关闭(默认,不暂停)。
 */
final class MessageTracker {

    /** 暂停/恢复读的回调(生产环境接 NioSession.pauseReads/resumeReads → worker 翻 OP_READ)。 */
    interface PauseResume {
        void pauseReads();

        void resumeReads();
    }

    private final PauseResume ctl;
    private final int limit;
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private volatile boolean paused = false;

    MessageTracker(PauseResume ctl, int limit) {
        this.ctl = ctl;
        this.limit = limit;
    }

    void onReceived() {
        if (limit <= 0) {
            return;
        }
        if (inFlight.incrementAndGet() >= limit && !paused) {
            paused = true;
            ctl.pauseReads();
        }
    }

    void onProcessed() {
        if (limit <= 0) {
            return;
        }
        int v = inFlight.decrementAndGet();
        if (v < limit && paused) {
            paused = false;
            ctl.resumeReads();
        }
    }

    boolean isPaused() {
        return paused;
    }
}
