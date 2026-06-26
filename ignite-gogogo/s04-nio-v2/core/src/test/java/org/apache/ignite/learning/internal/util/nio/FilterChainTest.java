package org.apache.ignite.learning.internal.util.nio;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 过滤链双向遍历顺序的纯逻辑测试。 */
class FilterChainTest {

    /** 记录过滤器:记下自己被经过的次序,然后继续转发。 */
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

    @Test
    void inboundWalksWireToApp() {
        List<String> log = new ArrayList<>();
        FilterChain ch = FilterChain.link(List.of(new Rec("A", log), new Rec("B", log), new Rec("C", log)));
        ch.fireInbound(null, "x"); // wire→app: A→B→C
        assertEquals(List.of("in:A", "in:B", "in:C"), log);
    }

    @Test
    void outboundWalksAppToWire() {
        List<String> log = new ArrayList<>();
        FilterChain ch = FilterChain.link(List.of(new Rec("A", log), new Rec("B", log), new Rec("C", log)));
        ch.fireOutbound(null, "y"); // app→wire: C→B→A
        assertEquals(List.of("out:C", "out:B", "out:A"), log);
    }
}
