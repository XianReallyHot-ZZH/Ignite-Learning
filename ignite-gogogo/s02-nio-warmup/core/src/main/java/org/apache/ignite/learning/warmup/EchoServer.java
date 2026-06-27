package org.apache.ignite.learning.warmup;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * 单线程 Selector echo server(NIO 热身):收啥回啥。S2 只是热身,S3 会把它"产品化"成多 worker NioServer。
 */
public final class EchoServer {

    private final Selector selector;
    private final ServerSocketChannel serverCh;
    private volatile boolean stopped;
    private Thread thread;

    public EchoServer(InetSocketAddress bind) throws IOException {
        this.selector = Selector.open();
        this.serverCh = ServerSocketChannel.open();
        serverCh.configureBlocking(false);
        serverCh.bind(bind);
        serverCh.register(selector, SelectionKey.OP_ACCEPT);
    }

    public InetSocketAddress localAddress() throws IOException {
        return (InetSocketAddress) serverCh.getLocalAddress();
    }

    public void start() {
        thread = new Thread(this::run, "echo-server");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        stopped = true;
        selector.wakeup();
        try {
            if (thread != null) {
                thread.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            serverCh.close();
        } catch (IOException ignored) {
            // ignore
        }
        try {
            selector.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    private void run() {
        try {
            while (!stopped) {
                selector.select(1000);
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove();
                    if (!k.isValid()) {
                        continue;
                    }
                    try {
                        if (k.isAcceptable()) {
                            accept();
                        } else if (k.isReadable()) {
                            echo((SocketChannel) k.channel());
                        }
                    } catch (IOException e) {
                        k.cancel();
                        try {
                            k.channel().close();
                        } catch (IOException ignored) {
                            // ignore
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (!stopped) {
                e.printStackTrace();
            }
        }
    }

    private void accept() throws IOException {
        SocketChannel ch = serverCh.accept();
        if (ch == null) {
            return;
        }
        ch.configureBlocking(false);
        ch.register(selector, SelectionKey.OP_READ);
    }

    private void echo(SocketChannel ch) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(1024);
        int n = ch.read(buf);
        if (n == -1) {
            ch.close();
            return;
        }
        if (n == 0) {
            return;
        }
        buf.flip(); // 写→读
        ch.write(buf); // 收啥回啥
    }
}
