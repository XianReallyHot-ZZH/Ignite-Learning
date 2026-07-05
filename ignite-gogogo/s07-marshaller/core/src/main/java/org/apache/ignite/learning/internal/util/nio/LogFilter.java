package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * 记录每条出入站消息(演示"可插拔过滤器";镜像 Ignite 里 tracer/log 类过滤器)。
 * 放在 codec 靠 app 一侧时,看到的是已解码的 {@code byte[]}(非原始 ByteBuffer)。
 */
final class LogFilter extends Filter {

    private final Consumer<String> sink;

    LogFilter(Consumer<String> sink) {
        this.sink = sink;
    }

    @Override
    void onInbound(NioSession ses, Object msg) {
        sink.accept("IN  " + describe(msg));
        proceedIn(ses, msg);
    }

    @Override
    void onOutbound(NioSession ses, Object msg) {
        sink.accept("OUT " + describe(msg));
        proceedOut(ses, msg);
    }

    private static String describe(Object msg) {
        if (msg instanceof byte[] b) {
            return "bytes[" + b.length + "]";
        }
        if (msg instanceof ByteBuffer bb) {
            return "buf[" + bb.remaining() + "]";
        }
        return String.valueOf(msg);
    }
}
