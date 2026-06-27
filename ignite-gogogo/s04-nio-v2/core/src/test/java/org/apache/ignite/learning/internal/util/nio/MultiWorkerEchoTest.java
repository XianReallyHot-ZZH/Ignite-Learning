package org.apache.ignite.learning.internal.util.nio;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多 worker echo 集成测试(端到端,真实 socket)。
 *
 * <p>起 3 worker + 8 连接并发,验证两点:
 * <ol>
 *   <li><b>不串号</b>:每条连接的 echo 顺序与内容正确(并发下不会把 A 的回显发给 B);</li>
 *   <li><b>分散</b>:8 连接被 Balancer 分到 ≥2 个 worker(验证轮询生效)。</li>
 * </ol>
 */
class MultiWorkerEchoTest {

    @Test
    void multiWorkerEchoAndDistribution() throws Exception {
        int workers = 3;
        int conns = 8;
        int perConn = 3; // 每连接发 3 条

        // AtomicReference 解决"listener 需要 server 才能调 send、而 server 构造又需要 listener"的循环依赖
        AtomicReference<NioServer> ref = new AtomicReference<>();
        // 记录所有连接被分配到的 workerId(用于断言"分散到 ≥2 个 worker")
        Set<Integer> workerIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

        // echo listener:收到啥就发啥(回显);连接建立时记录其 workerId
        NioServerListener echo = new NioServerListener() {
            @Override
            public void onConnected(NioSession s) {
                workerIds.add(s.workerId());
            }

            @Override
            public void onDisconnected(NioSession s) {
                // no-op
            }

            @Override
            public void onMessage(NioSession s, byte[] msg) {
                ref.get().send(s, msg); // 回显
            }
        };

        // 真实链:Head + [Codec, Log(silent)] + Tail。加 LogFilter 演示可插拔(空 sink 不刷屏)。
        NioServer server = new NioServer(new InetSocketAddress("127.0.0.1", 0), workers, echo,
                () -> List.of(new CodecFilter(), new LogFilter(s -> {
                })));
        ref.set(server);
        server.start();

        ExecutorService pool = Executors.newFixedThreadPool(conns);
        try {
            InetSocketAddress addr = server.localAddress();
            // 每个连接一个客户端任务,并发跑
            List<Future<List<String>>> futs = new ArrayList<>();
            for (int c = 0; c < conns; c++) {
                final int id = c;
                futs.add(pool.submit(() -> client(addr, id, perConn)));
            }
            // 逐个收集并断言:每连接收到的 echo == 它自己发的(顺序 + 内容)
            for (int c = 0; c < conns; c++) {
                List<String> got = futs.get(c).get(30, TimeUnit.SECONDS);
                List<String> expected = new ArrayList<>();
                for (int i = 0; i < perConn; i++) {
                    expected.add("c" + c + "-m" + i);
                }
                assertEquals(expected, got, "conn " + c + " echo mismatch / cross-talk");
            }
            // 8 连接应被分散到 ≥2 个 worker(若是 1 个,Balancer 没生效)
            assertTrue(workerIds.size() >= 2, "connections should spread across >=2 workers, got " + workerIds);
        } finally {
            pool.shutdownNow();
            server.stop();
        }
    }

    /** 单个阻塞 Socket 客户端:连接 → 发 perConn 条帧 → 收回显 → 返回收到的字符串列表。 */
    private static List<String> client(InetSocketAddress addr, int id, int perConn) throws Exception {
        try (Socket sock = new Socket()) {
            sock.connect(addr, 2000);
            sock.setSoTimeout(3000); // 读超时,防服务端没回显时卡死
            var out = sock.getOutputStream();
            var in = sock.getInputStream();

            // 发送:每条消息用长度前缀帧包装
            for (int i = 0; i < perConn; i++) {
                out.write(FrameCodec.encode(("c" + id + "-m" + i).getBytes()).array());
            }
            out.flush();

            // 接收:用同一个 Decoder 解回显帧(可能粘包/半包)
            FrameCodec.Decoder dec = new FrameCodec.Decoder();
            List<String> got = new ArrayList<>();
            byte[] tmp = new byte[1024];
            long deadline = System.currentTimeMillis() + 5000; // 5s 超时保护
            while (got.size() < perConn && System.currentTimeMillis() < deadline) {
                int n = in.read(tmp);
                if (n <= 0) {
                    break;
                }
                ByteBuffer buf = ByteBuffer.wrap(tmp, 0, n);
                for (byte[] f : dec.decode(buf)) {
                    got.add(new String(f));
                }
            }
            return got;
        }
    }
}
