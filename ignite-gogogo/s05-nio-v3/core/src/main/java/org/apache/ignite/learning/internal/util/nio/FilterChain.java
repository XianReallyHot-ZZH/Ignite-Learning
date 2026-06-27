package org.apache.ignite.learning.internal.util.nio;

import java.util.ArrayList;
import java.util.List;

/**
 * 双向过滤链(镜像 {@code GridNioFilterChain})。
 * <p>ordered 为 wire→app 顺序:[HeadFilter, middle..., TailFilter]。
 * inbound 从 head 起、沿 inNext 向 app;outbound 从 tail 起、沿 outNext 向 wire。
 */
final class FilterChain {

    private final Filter head;
    private final Filter tail;

    private FilterChain(Filter head, Filter tail) {
        this.head = head;
        this.tail = tail;
    }

    /** 按 wire→app 顺序装配并连接 inNext/outNext。 */
    static FilterChain link(List<Filter> orderedWireToApp) {
        for (int i = 0; i < orderedWireToApp.size(); i++) {
            orderedWireToApp.get(i).inNext = (i + 1 < orderedWireToApp.size()) ? orderedWireToApp.get(i + 1) : null;
            orderedWireToApp.get(i).outNext = (i - 1 >= 0) ? orderedWireToApp.get(i - 1) : null;
        }
        return new FilterChain(orderedWireToApp.get(0), orderedWireToApp.get(orderedWireToApp.size() - 1));
    }

    /** 装一条标准链:Head + middle + Tail(tail 终结到 listener)。middle 为 wire→app 顺序。 */
    static FilterChain create(List<Filter> middle, NioServerListener listener) {
        List<Filter> all = new ArrayList<>();
        all.add(new HeadFilter());
        all.addAll(middle);
        all.add(new TailFilter(listener));
        return link(all);
    }

    /** inbound 入口(wire→app):从 head 开始。 */
    void fireInbound(NioSession ses, Object msg) {
        head.onInbound(ses, msg);
    }

    /** outbound 入口(app→wire):从 tail 开始。 */
    void fireOutbound(NioSession ses, Object msg) {
        tail.onOutbound(ses, msg);
    }
}
