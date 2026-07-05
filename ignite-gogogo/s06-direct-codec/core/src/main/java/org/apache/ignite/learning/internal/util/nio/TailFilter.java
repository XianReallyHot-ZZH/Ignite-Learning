package org.apache.ignite.learning.internal.util.nio;

/**
 * 链的 app 侧端点(镜像 {@code TailFilter}):
 * inbound 终结——交给 {@code listener.onMessage(T)};outbound 直通(从 {@code send} 进入链)。
 */
final class TailFilter<T> extends Filter {

    private final NioServerListener<T> listener;

    TailFilter(NioServerListener<T> listener) {
        this.listener = listener;
    }

    @Override
    void onInbound(NioSession ses, Object msg) {
        @SuppressWarnings("unchecked") // 链内消息到这一步必为 T(由上游 codec 解出),转换安全
        T typed = (T) msg;
        listener.onMessage(ses, typed);
    }

    @Override
    void onOutbound(NioSession ses, Object msg) {
        proceedOut(ses, msg);
    }
}
