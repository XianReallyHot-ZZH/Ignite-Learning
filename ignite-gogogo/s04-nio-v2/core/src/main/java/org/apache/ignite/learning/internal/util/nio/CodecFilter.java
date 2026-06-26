package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * 协议过滤器(镜像 {@code GridNioCodecFilter},用最简长度前缀协议):
 * inbound 把 {@link ByteBuffer} 解码成若干 {@code byte[]};outbound 把 {@code byte[]} 编码成 {@link ByteBuffer}。
 * 每会话持有自己的解码器(线程安全:只在其 owning worker 线程被调)。
 */
final class CodecFilter extends Filter {

    private final FrameCodec.Decoder decoder = new FrameCodec.Decoder();

    @Override
    void onInbound(NioSession ses, Object msg) {
        for (byte[] frame : decoder.decode((ByteBuffer) msg)) {
            proceedIn(ses, frame);
        }
    }

    @Override
    void onOutbound(NioSession ses, Object msg) {
        proceedOut(ses, FrameCodec.encode((byte[]) msg));
    }
}
