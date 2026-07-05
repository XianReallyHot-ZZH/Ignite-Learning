package org.apache.ignite.learning.internal.util.nio;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 接收侧背压(镜像 {@code GridNioMessageTracker}):统计在途未处理消息,达上限暂停读({@code OP_READ}),处理完恢复。
 *
 * <p>语义:每收到一条({@link #onReceived})在途 +1,达 {@code limit} 暂停读(让对端别再灌);
 * 每处理完一条({@link #onProcessed})在途 −1,低于 {@code limit} 则恢复读。
 * {@code limit <= 0} = 关闭(默认,不暂停)。</p>
 */
final class MessageTracker {

    /** 暂停/恢复读的回调(生产环境接 NioSession.pauseReads/resumeReads → worker 翻 OP_READ)。 */
    interface PauseResume {
        void pauseReads();

        void resumeReads();
    }

    private final PauseResume ctl;
    private final int limit;
    /** 在途未处理消息数(收到未处理完的)。 */
    private final AtomicInteger inFlight = new AtomicInteger(0);
    /** 当前是否已暂停读(避免重复 pause/resume)。 */
    private volatile boolean paused = false;

    MessageTracker(PauseResume ctl, int limit) {
        this.ctl = ctl;
        this.limit = limit;
    }

    /** 收到一条:在途 +1;达上限且未暂停 → 暂停读。 */
    void onReceived() {
        if (limit <= 0) {
            return;
        }
        if (inFlight.incrementAndGet() >= limit && !paused) {
            paused = true;
            ctl.pauseReads(); // 关 OP_READ,让对端停止发送
        }
    }

    /** 处理完一条:在途 −1;低于上限且已暂停 → 恢复读。 */
    void onProcessed() {
        if (limit <= 0) {
            return;
        }
        int v = inFlight.decrementAndGet();
        if (v < limit && paused) {
            paused = false;
            ctl.resumeReads(); // 重新开 OP_READ
        }
    }

    boolean isPaused() {
        return paused;
    }
}
