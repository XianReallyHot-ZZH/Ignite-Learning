package org.apache.ignite.learning.internal.util.nio;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ignite.learning.internal.direct.DirectMessageReader;
import org.apache.ignite.learning.internal.direct.DirectMessageWriter;
import org.apache.ignite.learning.internal.direct.Message;
import org.apache.ignite.learning.internal.direct.MessageFactory;
import org.apache.ignite.learning.internal.direct.PingMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 端到端:经 NioServer(过滤链 CodecFilter→MessageCodecFilter)收发结构化 {@link PingMessage}。
 *
 * <p>真实 socket:client(raw Socket)按 [4字节长度][2字节 type][字段] 发送 → 服务端 inbound 解码出
 * PingMessage(listener 断言字段)→ 回显 → client 读回显帧解码 → 断言往返一致。
 * 验证 {@link MessageCodecFilter} seam 与 Phase 1 {@link CodecFilter} 帧的正确叠合。
 */
class PingMessageOverNioTest {

    @Test
    void echoRoundtrip() throws Exception {
        MessageFactory factory = new MessageFactory();
        factory.register(PingMessage.TYPE, PingMessage::new);
        factory.initialized();

        AtomicReference<NioServer<Message>> ref = new AtomicReference<>();
        AtomicReference<PingMessage> serverGot = new AtomicReference<>();

        // 收到 PingMessage → 记录 + 回显(触发 outbound 编码链)
        NioServerListener<Message> echo = new NioServerListener<>() {
            @Override
            public void onConnected(NioSession s) {
                // no-op
            }

            @Override
            public void onDisconnected(NioSession s) {
                // no-op
            }

            @Override
            public void onMessage(NioSession s, Message msg) {
                serverGot.set((PingMessage) msg);
                ref.get().send(s, msg); // 回显
            }
        };

        // 过滤链:Head → [CodecFilter(帧), MessageCodecFilter(Message↔byte[])] → Tail
        NioServer<Message> server = new NioServer<>(
                new InetSocketAddress("127.0.0.1", 0), 2, echo,
                () -> List.of(new CodecFilter(), new MessageCodecFilter(factory)));
        ref.set(server);
        server.start();

        try {
            InetSocketAddress addr = server.localAddress();

            PingMessage sent = new PingMessage(7L, "ping-over-nio", new byte[]{1, 2, 3, (byte) 0xFF});
            PingMessage echoed = clientRoundtrip(addr, factory, sent);

            // inbound:服务端 listener 收到的 PingMessage 字段一致
            assertNotNull(serverGot.get(), "server listener did not receive PingMessage");
            assertEquals(sent, serverGot.get());

            // outbound:client 读到的回显 PingMessage 字段一致
            assertNotNull(echoed, "client did not receive echo");
            assertEquals(sent, echoed);
        } finally {
            server.stop();
        }
    }

    /**
     * raw Socket client:发送 PingMessage 帧 → 读回显帧 → 解码回 PingMessage。
     */
    private static PingMessage clientRoundtrip(InetSocketAddress addr, MessageFactory factory, PingMessage sent)
            throws Exception {
        try (Socket sock = new Socket()) {
            sock.connect(addr, 2000);
            sock.setSoTimeout(3000);
            var out = sock.getOutputStream();
            var in = sock.getInputStream();

            // 发送:先按 Message wire 编码 [type][字段],再用 FrameCodec 包长度前缀
            out.write(FrameCodec.encode(encodeMessage(sent)).array());
            out.flush();

            // 接收:用 FrameCodec.Decoder 解长度前缀帧,再用 reader 解码 Message
            FrameCodec.Decoder dec = new FrameCodec.Decoder();
            byte[] tmp = new byte[1024];
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                int n = in.read(tmp);
                if (n <= 0) {
                    break;
                }
                for (byte[] frame : dec.decode(ByteBuffer.wrap(tmp, 0, n))) {
                    return decodeMessage(factory, frame); // 收到回显
                }
            }
            return null;
        }
    }

    /** 复刻 MessageCodecFilter outbound:写 [type][字段]。 */
    private static byte[] encodeMessage(PingMessage m) {
        DirectMessageWriter w = new DirectMessageWriter();
        w.startWrite();
        w.writeShort(m.directType());
        m.writeTo(w);
        return w.writtenBytes();
    }

    /** 复刻 MessageCodecFilter inbound:读 [type]→create→readFrom。 */
    private static PingMessage decodeMessage(MessageFactory factory, byte[] frame) {
        ByteBuffer buf = ByteBuffer.wrap(frame);
        short type = buf.getShort();
        PingMessage pm = (PingMessage) factory.create(type);
        DirectMessageReader r = new DirectMessageReader(factory);
        r.setReadBuffer(buf);
        pm.readFrom(r);
        return pm;
    }
}
