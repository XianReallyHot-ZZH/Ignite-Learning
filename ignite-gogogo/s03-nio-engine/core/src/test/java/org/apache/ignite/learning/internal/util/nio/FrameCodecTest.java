package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帧编解码单元测试(纯逻辑,确定性,不依赖网络)。
 * 覆盖编解码往返、粘包(多条拼一次到达)、半包(一条分多次到达)、空载荷、逐字节极端分片。
 */
class FrameCodecTest {

    /** 基本往返:编码一条 → 解码 → 拿回原内容。 */
    @Test
    void roundtripSingle() {
        FrameCodec.Decoder d = new FrameCodec.Decoder();
        List<byte[]> r = d.decode(FrameCodec.encode("hello".getBytes()));
        assertEquals(1, r.size());
        assertArrayEquals("hello".getBytes(), r.get(0));
    }

    /** 粘包:两条消息的字节拼在一个 ByteBuffer 里一次喂入,应解出 2 条且顺序正确。 */
    @Test
    void stickyPackets() {
        ByteBuffer a = FrameCodec.encode("AA".getBytes());
        ByteBuffer b = FrameCodec.encode("BBBB".getBytes());
        // 把两帧拼到一个 buffer(模拟 TCP 一次 read 读到多条)
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

    /** 半包:一条消息的字节分两次喂入(先 2 字节、再 5 字节),第一次不应产出,第二次才解出完整消息。 */
    @Test
    void halfPacketsAcrossCalls() {
        ByteBuffer full = FrameCodec.encode("XYZ".getBytes()); // 4 + 3 = 7 bytes
        ByteBuffer part1 = full.duplicate();
        part1.limit(2); // 只给前 2 字节(连 4 字节长度头都没凑齐)
        ByteBuffer part2 = full.duplicate();
        part2.position(2); // 剩余 5 字节

        FrameCodec.Decoder d = new FrameCodec.Decoder();
        assertTrue(d.decode(part1).isEmpty(), "半包不应产出消息");
        List<byte[]> r = d.decode(part2);
        assertEquals(1, r.size());
        assertArrayEquals("XYZ".getBytes(), r.get(0));
    }

    /** 空载荷(长度=0):合法边界,应解出一条空 byte[]。 */
    @Test
    void emptyPayload() {
        FrameCodec.Decoder d = new FrameCodec.Decoder();
        List<byte[]> r = d.decode(FrameCodec.encode(new byte[0]));
        assertEquals(1, r.size());
        assertArrayEquals(new byte[0], r.get(0));
    }

    /** 最严苛:逐字节喂入(每次只给 1 字节),仍应正确拼出多条消息(验证状态机的跨调用保留)。 */
    @Test
    void byteByByteFeed() {
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
            got.addAll(d.decode(one)); // 每次只喂 1 字节,状态机必须跨调用保留进度
        }
        assertEquals(2, got.size());
        assertArrayEquals("1".getBytes(), got.get(0));
        assertArrayEquals("22".getBytes(), got.get(1));
    }
}
