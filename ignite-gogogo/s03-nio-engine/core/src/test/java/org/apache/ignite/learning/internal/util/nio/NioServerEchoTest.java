package org.apache.ignite.learning.internal.util.nio;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * NioServer 回显集成测试:起一个 echo server,用阻塞 Socket 客户端发若干帧,断言原样回显。
 * 验证 v1 全链路:accept → read → 帧解码 → listener 回调 → send(入队)→ OP_WRITE → channel.write。
 */
class NioServerEchoTest {

    @Test
    void echoRoundtrip() throws Exception {
        final AtomicReference<NioServer> ref = new AtomicReference<>();
        NioServerListener echo = new NioServerListener() {
            @Override public void onConnected(NioSession ses) { }
            @Override public void onDisconnected(NioSession ses) { }
            @Override public void onMessage(NioSession ses, byte[] msg) {
                ref.get().send(ses, msg); // 回显
            }
        };

        NioServer server = new NioServer(new InetSocketAddress("127.0.0.1", 0), echo);
        ref.set(server);
        server.start();

        try {
            InetSocketAddress addr = server.localAddress();
            String[] msgs = {"one", "two", "three"};

            try (Socket sock = new Socket()) {
                sock.connect(addr, 2000);
                sock.setSoTimeout(2000);
                var out = sock.getOutputStream();
                var in = sock.getInputStream();

                // 客户端用同样的长度前缀协议发送
                for (String m : msgs) {
                    byte[] p = m.getBytes();
                    out.write(FrameCodec.encode(p).array());
                }
                out.flush();

                // 客户端用 FrameCodec.Decoder 收回显
                FrameCodec.Decoder dec = new FrameCodec.Decoder();
                List<String> got = new ArrayList<>();
                long deadline = System.currentTimeMillis() + 4000;
                byte[] tmp = new byte[1024];
                while (got.size() < msgs.length && System.currentTimeMillis() < deadline) {
                    int n = in.read(tmp);
                    if (n <= 0) {
                        break;
                    }
                    ByteBuffer buf = ByteBuffer.wrap(tmp, 0, n);
                    for (byte[] f : dec.decode(buf)) {
                        got.add(new String(f));
                    }
                }
                assertArrayEquals(msgs, got.toArray(new String[0]));
            }
        } finally {
            server.stop();
        }
    }
}
