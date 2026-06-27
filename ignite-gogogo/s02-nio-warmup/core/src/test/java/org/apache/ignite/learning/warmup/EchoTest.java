package org.apache.ignite.learning.warmup;

import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * 热身 echo 往返测试(S2 验收点):起单线程 Selector echo server,用阻塞 Socket 客户端发收,断言原样回。
 * 验证 NIO 三件套(Selector/Channel/ByteBuffer)的基本用法——这是 S3 NioServer 的前置技能。
 */
class EchoTest {

    /**
     * echo 全链路:起 server(端口 0 让 OS 分配)→ 客户端连上 → 发 "hello-nio" → server 原样回 → 客户端断言一致。
     * 用阻塞 Socket 做客户端(测试越简单越好,客户端只需验证服务端逻辑)。
     */
    @Test
    void echoRoundtrip() throws Exception {
        EchoServer server = new EchoServer(new InetSocketAddress("127.0.0.1", 0));
        server.start();
        try {
            InetSocketAddress addr = server.localAddress(); // start 后拿实际端口
            try (Socket sock = new Socket()) {
                sock.connect(addr, 2000);  // 连接超时 2s
                sock.setSoTimeout(2000);   // 读超时 2s(防卡死)
                var out = sock.getOutputStream();
                var in = sock.getInputStream();

                byte[] payload = "hello-nio".getBytes();
                out.write(payload);
                out.flush(); // 确保刷到 TCP

                byte[] got = in.readNBytes(payload.length); // 阻塞读到等长(echo 收啥回啥)
                assertArrayEquals(payload, got);
            }
        } finally {
            server.stop(); // 无论成败都停服务,释放端口与线程
        }
    }
}
