package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 帧编解码单元测试(纯逻辑,确定性)。覆盖往返、粘包、半包、空载荷、逐字节。 */
class FrameCodecTest {

    @Test
    void roundtripSingle() {
        FrameCodec.Decoder d = new FrameCodec.Decoder();
        List<byte[]> r = d.decode(FrameCodec.encode("hello".getBytes()));
        assertEquals(1, r.size());
        assertArrayEquals("hello".getBytes(), r.get(0));
    }

    @Test
    void stickyPackets() {
        ByteBuffer a = FrameCodec.encode("AA".getBytes());
        ByteBuffer b = FrameCodec.encode("BBBB".getBytes());
        ByteBuffer both = ByteBuffer.allocate(a.remaining() + b.remaining());
        both.put(a);
        both.put(b);
        both.flip();

        FrameCodec.Decoder d = new FrameCodec.Decoder();
        List<byte[]> r = d.decode(both);
        assertEquals(2, r.size());
        assertArrayEquals("AA".getBytes(), r.get(0));
        assertArrayEquals("BBBB".getBytes(), r.get(1));
    }

    @Test
    void halfPacketsAcrossCalls() {
        ByteBuffer full = FrameCodec.encode("XYZ".getBytes()); // 4 + 3 = 7 bytes
        ByteBuffer part1 = full.duplicate();
        part1.limit(2); // 2 bytes(长度字段都没凑齐)
        ByteBuffer part2 = full.duplicate();
        part2.position(2); // 剩余 5 字节

        FrameCodec.Decoder d = new FrameCodec.Decoder();
        assertTrue(d.decode(part1).isEmpty(), "半包不应产出消息");
        List<byte[]> r = d.decode(part2);
        assertEquals(1, r.size());
        assertArrayEquals("XYZ".getBytes(), r.get(0));
    }

    @Test
    void emptyPayload() {
        FrameCodec.Decoder d = new FrameCodec.Decoder();
        List<byte[]> r = d.decode(FrameCodec.encode(new byte[0]));
        assertEquals(1, r.size());
        assertArrayEquals(new byte[0], r.get(0));
    }

    @Test
    void byteByByteFeed() {
        // 最严苛:逐字节喂入,仍应正确拼出多条消息
        ByteBuffer m1 = FrameCodec.encode("1".getBytes());
        ByteBuffer m2 = FrameCodec.encode("22".getBytes());
        ByteBuffer combined = ByteBuffer.allocate(m1.remaining() + m2.remaining());
        combined.put(m1);
        combined.put(m2);
        combined.flip();

        byte[] all = new byte[combined.remaining()];
        combined.get(all);

        FrameCodec.Decoder d = new FrameCodec.Decoder();
        List<byte[]> got = new ArrayList<>();
        for (byte x : all) {
            ByteBuffer one = ByteBuffer.allocate(1);
            one.put(x);
            one.flip();
            got.addAll(d.decode(one));
        }
        assertEquals(2, got.size());
        assertArrayEquals("1".getBytes(), got.get(0));
        assertArrayEquals("22".getBytes(), got.get(1));
    }
}
