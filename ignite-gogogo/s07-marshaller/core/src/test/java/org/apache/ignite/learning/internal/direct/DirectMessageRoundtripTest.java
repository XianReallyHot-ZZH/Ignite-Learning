package org.apache.ignite.learning.internal.direct;

import java.nio.ByteBuffer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * reader/writer 往返一致性测试(镜像 {@code DirectByteBufferStream} 的读写对称)。
 * 往返 helper 复刻 {@code MessageCodecFilter} 的 encode/decode:写 [type][字段]、读 [type]→create→readFrom。
 */
class DirectMessageRoundtripTest {

    /** 复刻 MessageCodecFilter 的 encode→decode 一来一回(不经 NIO,纯字节往返)。 */
    private static Message roundtrip(MessageFactory f, Message src) {
        // encode:[type][字段]
        DirectMessageWriter w = new DirectMessageWriter();
        w.startWrite();
        w.writeShort(src.directType());
        src.writeTo(w);
        byte[] bytes = w.writtenBytes();

        // decode:读 [type]→create→readFrom 字段
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        short type = buf.getShort();
        Message dst = f.create(type);
        DirectMessageReader r = new DirectMessageReader(f);
        r.setReadBuffer(buf);
        dst.readFrom(r);
        return dst;
    }

    private static MessageFactory factory() {
        MessageFactory f = new MessageFactory();
        f.register(PingMessage.TYPE, PingMessage::new);
        f.register(SampleMessage.TYPE, SampleMessage::new);
        f.initialized();
        return f;
    }

    @Test
    void primitiveFieldsRoundtrip() {
        SampleMessage src = new SampleMessage();
        src.b = (byte) -1;
        src.s = (short) 32_000;
        src.i = Integer.MAX_VALUE;
        src.l = -123_456_789L;
        src.z = true;

        SampleMessage dst = (SampleMessage) roundtrip(factory(), src);

        assertEquals(src.b, dst.b);
        assertEquals(src.s, dst.s);
        assertEquals(src.i, dst.i);
        assertEquals(src.l, dst.l);
        assertEquals(src.z, dst.z);
    }

    @Test
    void complexFieldsRoundtrip() {
        SampleMessage src = new SampleMessage();
        src.ba = new byte[]{1, 2, 3, (byte) 0xFF};
        src.ia = new int[]{Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE};
        src.la = new long[]{0L, -1L, Long.MAX_VALUE};
        src.str = "Hello-ÜTF8-中文"; // 非 ASCII,验证 UTF-8 编码往返
        src.uid = new UUID(0x123456789abcdef0L, 0x0fedcba987654321L);

        SampleMessage dst = (SampleMessage) roundtrip(factory(), src);

        assertArrayEquals(src.ba, dst.ba);
        assertArrayEquals(src.ia, dst.ia);
        assertArrayEquals(src.la, dst.la);
        assertEquals(src.str, dst.str);
        assertEquals(src.uid, dst.uid);
    }

    @Test
    void nestedMessageRoundtrip() {
        SampleMessage src = new SampleMessage();
        src.inner = new PingMessage(42L, "nested-payload", new byte[]{9, 9, 9});

        SampleMessage dst = (SampleMessage) roundtrip(factory(), src);

        // 嵌套消息经 writeMessage/readMessage(type + 字段)完整往返
        assertEquals(src.inner, dst.inner);
    }

    @Test
    void nullFieldsRoundtrip() {
        // 全默认:原语 0/false、数组/String/UUID/inner 为 null —— 验证 null 编码往返
        SampleMessage src = new SampleMessage();

        SampleMessage dst = (SampleMessage) roundtrip(factory(), src);

        assertNull(dst.ba);
        assertNull(dst.ia);
        assertNull(dst.la);
        assertNull(dst.str);
        assertNull(dst.uid);
        assertNull(dst.inner);
    }

    @Test
    void nestedNullMessageRoundtrip() {
        SampleMessage src = new SampleMessage();
        src.inner = null; // 嵌套消息为 null(Short.MIN_VALUE 标记)

        SampleMessage dst = (SampleMessage) roundtrip(factory(), src);

        assertNull(dst.inner);
    }
}
