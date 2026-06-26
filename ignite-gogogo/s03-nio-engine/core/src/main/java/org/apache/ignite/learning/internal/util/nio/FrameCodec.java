package org.apache.ignite.learning.internal.util.nio;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 长度前缀帧:[4 字节大端长度][载荷]。镜像 Ignite {@code GridBufferedParser} / {@code GridNioServerBuffer} 路线
 * (v1 用最简长度前缀协议;生产用 {@code GridDirectParser} 的 2 字节 direct-type,留到对照)。
 */
public final class FrameCodec {

    /** 长度字段字节数。 */
    public static final int LENGTH_BYTES = 4;

    private FrameCodec() {
    }

    /** 编码一条消息为 [长度][载荷] 的 ByteBuffer(已 flip,可直接写 channel)。 */
    public static ByteBuffer encode(byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(LENGTH_BYTES + payload.length);
        buf.putInt(payload.length);
        buf.put(payload);
        buf.flip();
        return buf;
    }

    /**
     * 有状态的帧解码器:可多次 feed ByteBuffer,跨调用保留半包状态,正确处理粘包/半包。
     * 镜像 {@code GridNioServerBuffer.read()} 的"先凑齐长度、再凑齐载荷"状态机。
     */
    public static final class Decoder {
        private static final int READING_LENGTH = 0;
        private static final int READING_PAYLOAD = 1;

        private int state = READING_LENGTH;
        private final ByteBuffer lenBuf = ByteBuffer.allocate(LENGTH_BYTES);
        private int payloadLen = -1;
        private ByteBuffer payloadBuf = null;

        /** 喂入若干字节,返回本次解出的完整消息(0~N 条)。 */
        public List<byte[]> decode(ByteBuffer in) {
            List<byte[]> out = new ArrayList<>();
            while (in.hasRemaining()) {
                if (state == READING_LENGTH) {
                    copy(in, lenBuf);
                    if (!lenBuf.hasRemaining()) {
                        lenBuf.flip();
                        payloadLen = lenBuf.getInt();
                        if (payloadLen < 0) {
                            throw new IllegalStateException("negative frame length: " + payloadLen);
                        }
                        payloadBuf = ByteBuffer.allocate(payloadLen);
                        state = READING_PAYLOAD;
                    }
                }
                if (state == READING_PAYLOAD) {
                    copy(in, payloadBuf);
                    if (!payloadBuf.hasRemaining()) {
                        out.add(payloadBuf.array());
                        reset();
                    }
                }
            }
            return out;
        }

        private void reset() {
            state = READING_LENGTH;
            lenBuf.clear();
            payloadLen = -1;
            payloadBuf = null;
        }

        private static void copy(ByteBuffer src, ByteBuffer dst) {
            int n = Math.min(src.remaining(), dst.remaining());
            if (n <= 0) {
                return;
            }
            if (src.hasArray() && dst.hasArray()) {
                System.arraycopy(src.array(), src.arrayOffset() + src.position(),
                        dst.array(), dst.arrayOffset() + dst.position(), n);
                src.position(src.position() + n);
                dst.position(dst.position() + n);
            } else {
                for (int i = 0; i < n; i++) {
                    dst.put(src.get());
                }
            }
        }
    }
}
