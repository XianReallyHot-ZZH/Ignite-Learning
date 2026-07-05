package org.apache.ignite.learning.internal.marshaller;

import java.nio.ByteBuffer;
import org.apache.ignite.learning.internal.direct.DirectMessageReader;
import org.apache.ignite.learning.internal.direct.DirectMessageWriter;
import org.apache.ignite.learning.internal.direct.MessageFactory;
import org.apache.ignite.learning.internal.direct.PingMessage;
import org.apache.ignite.learning.internal.marshaller.optimized.OptimizedMarshaller;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Marshaller ↔ Direct 分工 seam 测试(P02 §3.3):
 * 用户对象 → Marshaller → {@code byte[]} → Direct 信封的 {@code writeByteArray} 字段
 * → Direct 编/解码往返 → {@code readByteArray} → Marshaller → 用户对象,字段等价。
 *
 * <p>证明:Direct(固定信封)与 Marshaller(任意载荷)以 {@code byte[]} 解耦,二者独立可用。
 */
class MarshallerViaDirectTest {

    @Test
    void objectOverDirectMessage() throws Exception {
        OptimizedMarshaller marsh = new OptimizedMarshaller();
        MessageFactory factory = new MessageFactory();
        factory.register(PingMessage.TYPE, PingMessage::new);
        factory.initialized();

        // 1) 用户对象 → Marshaller → byte[](载荷层)
        Person user = new Person("Carol", 40, 7L, true, "c@x.io", new byte[]{9, 9, 9});
        byte[] payload = marsh.marshal(user);

        // 2) byte[] 搭进 Direct 信封(PingMessage.data,即 S6 的 writeByteArray seam)
        PingMessage env = new PingMessage(1L, "cache-value", payload);

        // 3) Direct 编码 [type][字段] → 字节 → Direct 解码
        DirectMessageWriter w = new DirectMessageWriter();
        w.startWrite();
        w.writeShort(env.directType()); // 顶层 type 由调用方写
        env.writeTo(w);                  // 字段(id/payload/data)
        ByteBuffer buf = ByteBuffer.wrap(w.writtenBytes());

        short type = buf.getShort();
        PingMessage env2 = (PingMessage) factory.create(type);
        DirectMessageReader r = new DirectMessageReader(factory);
        r.setReadBuffer(buf);
        env2.readFrom(r);

        // 4) 取载荷 → Marshaller → 用户对象,字段等价(seam 闭环)
        assertArrayEquals(payload, env2.data);
        Person user2 = marsh.unmarshal(env2.data, null);
        assertEquals(user, user2);
    }
}
