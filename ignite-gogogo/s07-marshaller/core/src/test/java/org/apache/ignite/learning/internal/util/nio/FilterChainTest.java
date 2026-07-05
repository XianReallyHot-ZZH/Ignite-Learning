package org.apache.ignite.learning.internal.util.nio;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 过滤链<b>双向遍历顺序</b>的纯逻辑测试(不依赖真实 session/网络)。
 * 验证:inbound 从 head 沿 inNext 走向 app;outbound 从 tail 沿 outNext 走向 wire(即反向)。
 */
class FilterChainTest {

    /**
     * 记录过滤器:被经过时把自己的名字记进 log,然后继续转发(proceedIn/proceedOut)。
     * 用它拼一条链,事后看 log 的顺序就知道遍历方向对不对。
     */
    static final class Rec extends Filter {
        final String name;
        final List<String> log;

        Rec(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        void onInbound(NioSession ses, Object msg) {
            log.add("in:" + name);
            proceedIn(ses, msg);
        }

        @Override
        void onOutbound(NioSession ses, Object msg) {
            log.add("out:" + name);
            proceedOut(ses, msg);
        }
    }

    /** inbound(wire→app):链按 wire→app 顺序 A→B→C 遍历。 */
    @Test
    void inboundWalksWireToApp() {
        List<String> log = new ArrayList<>();
        FilterChain ch = FilterChain.link(List.of(new Rec("A", log), new Rec("B", log), new Rec("C", log)));
        ch.fireInbound(null, "x"); // 从 head 进入
        assertEquals(List.of("in:A", "in:B", "in:C"), log);
    }

    /** outbound(app→wire):从 tail 进入,反向遍历 C→B→A。 */
    @Test
    void outboundWalksAppToWire() {
        List<String> log = new ArrayList<>();
        FilterChain ch = FilterChain.link(List.of(new Rec("A", log), new Rec("B", log), new Rec("C", log)));
        ch.fireOutbound(null, "y"); // 从 tail 进入
        assertEquals(List.of("out:C", "out:B", "out:A"), log);
    }
}
