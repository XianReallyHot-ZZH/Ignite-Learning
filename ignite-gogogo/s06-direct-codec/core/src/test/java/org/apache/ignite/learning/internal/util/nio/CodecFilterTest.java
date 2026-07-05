package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@link CodecFilter} 的入站(解码 ByteBuffer→byte[])/ 出站(编码 byte[]→ByteBuffer)测试。
 * 用一个"终点过滤器"(terminal)接收链尽头传来的消息,验证 codec 的转换正确。
 */
class CodecFilterTest {

    /** 入站终点回调:接收链尽头(inbound 终点)的消息。 */
    @FunctionalInterface
    interface InSink {
        void take(NioSession s, Object m);
    }

    /** 出站终点回调:接收链头(outbound 终点,wire 侧)的消息。 */
    @FunctionalInterface
    interface OutSink {
        void take(NioSession s, Object m);
    }

    /** 造一个只把消息转给回调的终点过滤器(测试用,不做任何转换/proceed)。 */
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

    /** 入站:ByteBuffer 经 codec 解码成 byte[],终点收到。 */
    @Test
    void inboundDecodesBytesToMessage() {
        List<Object> got = new ArrayList<>();
        // 链(wire→app):codec → terminal;codec 把 ByteBuffer 解成 byte[]
        FilterChain ch = FilterChain.link(List.of(new CodecFilter(), terminal((s, m) -> got.add(m), (s, m) -> {
        })));
        ch.fireInbound(null, FrameCodec.encode("hi".getBytes()));
        assertEquals(1, got.size());
        assertArrayEquals("hi".getBytes(), (byte[]) got.get(0));
    }

    /** 出站:byte[] 经 codec 编码成 ByteBuffer,终点(wire 侧)收到;再解码回来验证内容一致。 */
    @Test
    void outboundEncodesMessageToBytes() {
        List<Object> got = new ArrayList<>();
        // 链(wire→app 顺序):terminal ← codec;出站(app→wire)从 codec 进、到 terminal 出
        FilterChain ch = FilterChain.link(List.of(terminal((s, m) -> {
        }, (s, m) -> got.add(m)), new CodecFilter()));
        ch.fireOutbound(null, "yo".getBytes());
        assertEquals(1, got.size());
        assertInstanceOf(ByteBuffer.class, got.get(0)); // 终点拿到的是已编码的 ByteBuffer
        // 再解码回来,验证编码内容正确
        List<byte[]> decoded = new FrameCodec.Decoder().decode((ByteBuffer) got.get(0));
        assertEquals(1, decoded.size());
        assertArrayEquals("yo".getBytes(), decoded.get(0));
    }
}
