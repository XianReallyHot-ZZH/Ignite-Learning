package org.apache.ignite.learning.internal.util.nio;

/**
 * ThreadLocal:标记"正在处理消息的线程"(即 worker 线程)。发送时旁路信号量防自死锁。
 * 镜像 {@code GridNioBackPressureControl}。
 */
final class GridBackPressureControl {

    private static final ThreadLocal<Boolean> IN_MSG = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private GridBackPressureControl() {
    }

    static boolean inMessageThread() {
        return IN_MSG.get();
    }

    /** worker 线程启动时调用一次:此后该线程的发送旁路发送信号量。 */
    static void enterMessageThread() {
        IN_MSG.set(Boolean.TRUE);
    }
}
