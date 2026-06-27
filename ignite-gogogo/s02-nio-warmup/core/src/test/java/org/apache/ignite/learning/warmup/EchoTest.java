package org.apache.ignite.learning.warmup;

import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** 热身 echo 往返测试:起 server,阻塞 Socket 客户端发收,断言原样回。 */
class EchoTest {

    @Test
    void echoRoundtrip() throws Exception {
        EchoServer server = new EchoServer(new InetSocketAddress("127.0.0.1", 0));
        server.start();
        try {
            InetSocketAddress addr = server.localAddress();
            try (Socket sock = new Socket()) {
                sock.connect(addr, 2000);
                sock.setSoTimeout(2000);
                var out = sock.getOutputStream();
                var in = sock.getInputStream();

                byte[] payload = "hello-nio".getBytes();
                out.write(payload);
                out.flush();

                byte[] got = in.readNBytes(payload.length); // 阻塞读到等长
                assertArrayEquals(payload, got);
            }
        } finally {
            server.stop();
        }
    }
}
