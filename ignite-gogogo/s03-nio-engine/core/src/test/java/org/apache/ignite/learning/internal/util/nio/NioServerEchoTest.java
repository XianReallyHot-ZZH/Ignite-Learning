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

    /**
     * 回显全链路测试:客户端发 3 条消息 → 服务端收到后原样回显 → 客户端断言收到的内容一致。
     *
     * <p>验证 v1 全链路:
     * <pre>
     *   服务端:accept → OP_READ → 帧解码(粘包/半包) → onMessage 回调 → send 入队 → OP_WRITE → channel.write
     *   客户端:阻塞 Socket write 发帧 → 阻塞 read 收回显 → FrameCodec.Decoder 解帧
     * </pre>
     *
     * <p>为什么用阻塞 Socket 做客户端:测试代码越简单越好,客户端只需验证服务端的 NIO 逻辑,
     * 用 JDK 经典阻塞 IO 即可,避免测试里再写一套 NIO 客户端增加复杂度。</p>
     */
    @Test
    void echoRoundtrip() throws Exception {
        // ===== 1. 搭建 echo server =====
        // AtomicReference 解决匿名内部类引用 server 的"先有鸡还是先有蛋"问题:
        // listener 需要 server 来调 send,但 server 构造又需要 listener,先建 ref 占位再 set
        final AtomicReference<NioServer> ref = new AtomicReference<>();
        NioServerListener echo = new NioServerListener() {
            @Override public void onConnected(NioSession ses) { }
            @Override public void onDisconnected(NioSession ses) { }
            @Override public void onMessage(NioSession ses, byte[] msg) {
                // 收到什么就发什么 → 回显(echo)
                ref.get().send(ses, msg);
            }
        };

        // 绑定 127.0.0.1 + 端口 0 = 让 OS 随机分配空闲端口(避免端口冲突)
        NioServer server = new NioServer(new InetSocketAddress("127.0.0.1", 0), echo);
        ref.set(server);
        server.start();

        try {
            // start 后才能拿到实际分配的端口
            InetSocketAddress addr = server.localAddress();
            String[] msgs = {"one", "two", "three"};

            // ===== 2. 客户端连接 + 发送 =====
            try (Socket sock = new Socket()) {
                sock.connect(addr, 2000);  // 连接超时 2s
                sock.setSoTimeout(2000);   // 读超时 2s(防止服务端没回显时测试卡死)
                var out = sock.getOutputStream();
                var in = sock.getInputStream();

                // 客户端用同样的长度前缀协议发送:每条消息经 FrameCodec.encode 包装成 [4字节长度][载荷]
                for (String m : msgs) {
                    byte[] p = m.getBytes();
                    out.write(FrameCodec.encode(p).array());
                }
                out.flush(); // 确保全部刷到 TCP 发送缓冲区

                // ===== 3. 客户端收回显 + 帧解码 =====
                // 服务端发回来的也是长度前缀帧,客户端用同一个 Decoder 解析
                FrameCodec.Decoder dec = new FrameCodec.Decoder();
                List<String> got = new ArrayList<>();
                // 超时保护:4s 内没收齐 3 条就跳出,避免测试无限挂起
                long deadline = System.currentTimeMillis() + 4000;
                byte[] tmp = new byte[1024];
                // 循环条件:还没收齐 且 没超时
                while (got.size() < msgs.length && System.currentTimeMillis() < deadline) {
                    int n = in.read(tmp);  // 阻塞读,可能一次返回多条帧(粘包)或不到一条(半包)
                    if (n <= 0) {
                        break; // 连接关闭或 EOF
                    }
                    // wrap 零拷贝包装:把 tmp[0..n) 直接包装成 buffer,共享数组不复制
                    ByteBuffer buf = ByteBuffer.wrap(tmp, 0, n);
                    // Decoder 处理粘包/半包:一次 read 可能解出 0~N 条完整消息
                    for (byte[] f : dec.decode(buf)) {
                        got.add(new String(f));
                    }
                }
                // 断言:收到的回显与发送的完全一致(顺序 + 内容)
                assertArrayEquals(msgs, got.toArray(new String[0]));
            }
        } finally {
            // 无论测试成功/失败都要停服务,释放端口和线程
            server.stop();
        }
    }
}
