package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** CodecFilter 的入站(解码)/出站(编码)测试。 */
class CodecFilterTest {

    @FunctionalInterface
    interface InSink {
        void take(NioSession s, Object m);
    }

    @FunctionalInterface
    interface OutSink {
        void take(NioSession s, Object m);
    }

    private static Filter terminal(InSink in, OutSink out) {
        return new Filter() {
            @Override
            void onInbound(NioSession s, Object m) {
                in.take(s, m);
            }

            @Override
            void onOutbound(NioSession s, Object m) {
                out.take(s, m);
            }
        };
    }

    @Test
    void inboundDecodesBytesToMessage() {
        List<Object> got = new ArrayList<>();
        // wire→app: codec(解码) → terminal 收 byte[]
        FilterChain ch = FilterChain.link(List.of(new CodecFilter(), terminal((s, m) -> got.add(m), (s, m) -> {
        })));
        ch.fireInbound(null, FrameCodec.encode("hi".getBytes()));
        assertEquals(1, got.size());
        assertArrayEquals("hi".getBytes(), (byte[]) got.get(0));
    }

    @Test
    void outboundEncodesMessageToBytes() {
        List<Object> got = new ArrayList<>();
        // wire→app: terminal(收出站) ← codec(编码);出站 app→wire: codec → terminal
        FilterChain ch = FilterChain.link(List.of(terminal((s, m) -> {
        }, (s, m) -> got.add(m)), new CodecFilter()));
        ch.fireOutbound(null, "yo".getBytes());
        assertEquals(1, got.size());
        assertInstanceOf(ByteBuffer.class, got.get(0));
        List<byte[]> decoded = new FrameCodec.Decoder().decode((ByteBuffer) got.get(0));
        assertEquals(1, decoded.size());
        assertArrayEquals("yo".getBytes(), decoded.get(0));
    }
}
