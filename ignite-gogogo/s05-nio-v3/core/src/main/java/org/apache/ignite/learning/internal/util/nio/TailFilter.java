package org.apache.ignite.learning.internal.util.nio;

/**
 * 链的 app 侧端点(镜像 {@code TailFilter}):
 * inbound 终结——交给 listener.onMessage(byte[]);outbound 直通(从 send 进入链)。
 */
final class TailFilter extends Filter {

    private final NioServerListener listener;

    TailFilter(NioServerListener listener) {
        this.listener = listener;
    }

    @Override
    void onInbound(NioSession ses, Object msg) {
        listener.onMessage(ses, (byte[]) msg);
    }

    @Override
    void onOutbound(NioSession ses, Object msg) {
        proceedOut(ses, msg);
    }
}
