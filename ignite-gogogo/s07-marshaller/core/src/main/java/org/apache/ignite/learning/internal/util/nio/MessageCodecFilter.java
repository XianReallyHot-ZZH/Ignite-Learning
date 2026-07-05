package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import org.apache.ignite.learning.internal.direct.DirectMessageReader;
import org.apache.ignite.learning.internal.direct.DirectMessageWriter;
import org.apache.ignite.learning.internal.direct.Message;
import org.apache.ignite.learning.internal.direct.MessageFactory;

/**
 * Direct 编解码 seam(镜像 {@code internal/util/nio/GridDirectParser}):把
 * {@code byte[]}(由 {@link CodecFilter} 长度前缀帧解出的<b>完整帧</b>)↔ 结构化 {@link Message}。
 *
 * <p>过滤链顺序(wire→app):{@code Head → CodecFilter(ByteBuffer↔byte[]) → MessageCodecFilter(byte[]↔Message)
 * → Tail(listener&lt;Message&gt;)}。
 *
 * <ul>
 *   <li><b>inbound(byte[]→Message)</b>:读 2 字节 directType → {@code factory.create} → {@code readFrom} 字段
 *       (buffer 已定位到 type 之后);</li>
 *   <li><b>outbound(Message→byte[])</b>:{@code writeShort(directType)} → {@code writeTo} 字段 → 取 {@code byte[]}。</li>
 * </ul>
 *
 * <p>v1:由上游 {@code CodecFilter} 保证 {@code byte[]} 是一条完整消息,故不做 partial-read 续读
 * (Ignite 的 {@code GridDirectParser} 把半消息暂存 session meta 跨读续读,v1 不需要)。
 *
 * <p>每消息新建 reader/writer —— 无跨消息状态,天然跨会话/跨 worker 安全(Ignite 按 session 复用,是性能优化,v1 从简)。
 */
public final class MessageCodecFilter extends Filter {

    private final MessageFactory factory;

    public MessageCodecFilter(MessageFactory factory) {
        this.factory = factory;
    }

    @Override
    void onInbound(NioSession ses, Object msg) {
        byte[] frame = (byte[]) msg;
        ByteBuffer buf = ByteBuffer.wrap(frame);
        short type = buf.getShort(); // 消费 2 字节 type(顶层消息的 type 由本 filter 读)
        Message m = factory.create(type);
        DirectMessageReader reader = new DirectMessageReader(factory);
        reader.setReadBuffer(buf); // buf 已定位到 type 之后,readFrom 只读字段
        m.readFrom(reader);
        proceedIn(ses, m);
    }

    @Override
    void onOutbound(NioSession ses, Object msg) {
        Message m = (Message) msg;
        DirectMessageWriter writer = new DirectMessageWriter();
        writer.startWrite();
        writer.writeShort(m.directType()); // 顶层消息的 type 由本 filter 写
        m.writeTo(writer);                  // 字段
        proceedOut(ses, writer.writtenBytes());
    }
}
