package org.apache.ignite.learning.internal.util.nio;

/**
 * 过滤链中的一个过滤器(镜像 {@code GridNioFilter})。双向语义:
 * <ul>
 *   <li>inbound(wire→app):{@link #onInbound};处理完调 {@link #proceedIn} 向 app 转发。</li>
 *   <li>outbound(app→wire):{@link #onOutbound};处理完调 {@link #proceedOut} 向 wire 转发。</li>
 * </ul>
 * {@code inNext}/{@code outNext} 由 {@link FilterChain} 装配。
 */
abstract class Filter {

    /** inbound 下一跳(向 app);由 FilterChain 装配,链尾为 null。 */
    Filter inNext;

    /** outbound 下一跳(向 wire);由 FilterChain 装配,链头(wire 侧)为 null。 */
    Filter outNext;

    /** 处理一条入站消息(从 wire 方向来)。 */
    abstract void onInbound(NioSession ses, Object msg);

    /** 处理一条出站消息(往 wire 方向去)。 */
    abstract void onOutbound(NioSession ses, Object msg);

    /** 继续向 app 转发 inbound。 */
    protected final void proceedIn(NioSession ses, Object msg) {
        if (inNext != null) {
            inNext.onInbound(ses, msg);
        }
    }

    /** 继续向 wire 转发 outbound。 */
    protected final void proceedOut(NioSession ses, Object msg) {
        if (outNext != null) {
            outNext.onOutbound(ses, msg);
        }
    }
}
