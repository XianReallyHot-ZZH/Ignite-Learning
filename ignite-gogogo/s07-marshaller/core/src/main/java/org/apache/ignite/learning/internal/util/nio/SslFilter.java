package org.apache.ignite.learning.internal.util.nio;

/**
 * SSL/TLS 过滤器**占位**(镜像 {@code GridNioSslFilter} 在过滤链中的位置)。本 session 不实现真实加密,直通。
 * 真实实现需包 {@code SSLEngine} + 握手 + {@code unwrap}/{@code wrap}(留后续)。
 */
final class SslFilter extends Filter {

    @Override
    void onInbound(NioSession ses, Object msg) {
        proceedIn(ses, msg);
    }

    @Override
    void onOutbound(NioSession ses, Object msg) {
        proceedOut(ses, msg);
    }
}
