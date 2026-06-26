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
 * 多 worker echo 集成测试:3 worker + 8 连接并发;断言每连接 echo 顺序正确(不串号),
 * 且连接被分散到 >=2 个 worker(验证 Balancer)。
 */
class MultiWorkerEchoTest {

    @Test
    void multiWorkerEchoAndDistribution() throws Exception {
        int workers = 3;
        int conns = 8;
        int perConn = 3;

        AtomicReference<NioServer> ref = new AtomicReference<>();
        Set<Integer> workerIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

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

        // 真实链:Head + [Codec, Log(silent)] + Tail。加 LogFilter 演示可插拔(用空 sink 不刷屏)。
        NioServer server = new NioServer(new InetSocketAddress("127.0.0.1", 0), workers, echo,
                () -> List.of(new CodecFilter(), new LogFilter(s -> {
                })));
        ref.set(server);
        server.start();

        ExecutorService pool = Executors.newFixedThreadPool(conns);
        try {
            InetSocketAddress addr = server.localAddress();
            List<Future<List<String>>> futs = new ArrayList<>();
            for (int c = 0; c < conns; c++) {
                final int id = c;
                futs.add(pool.submit(() -> client(addr, id, perConn)));
            }
            for (int c = 0; c < conns; c++) {
                List<String> got = futs.get(c).get(30, TimeUnit.SECONDS);
                List<String> expected = new ArrayList<>();
                for (int i = 0; i < perConn; i++) {
                    expected.add("c" + c + "-m" + i);
                }
                assertEquals(expected, got, "conn " + c + " echo mismatch / cross-talk");
            }
            assertTrue(workerIds.size() >= 2, "connections should spread across >=2 workers, got " + workerIds);
        } finally {
            pool.shutdownNow();
            server.stop();
        }
    }

    private static List<String> client(InetSocketAddress addr, int id, int perConn) throws Exception {
        try (Socket sock = new Socket()) {
            sock.connect(addr, 2000);
            sock.setSoTimeout(3000);
            var out = sock.getOutputStream();
            var in = sock.getInputStream();

            for (int i = 0; i < perConn; i++) {
                out.write(FrameCodec.encode(("c" + id + "-m" + i).getBytes()).array());
            }
            out.flush();

            FrameCodec.Decoder dec = new FrameCodec.Decoder();
            List<String> got = new ArrayList<>();
            byte[] tmp = new byte[1024];
            long deadline = System.currentTimeMillis() + 5000;
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
