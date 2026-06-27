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

    /**
     * 编码一条消息为 [长度][载荷] 的 ByteBuffer(已 flip,可直接写 channel)。
     *
     * <p>帧格式:[4字节大端长度][载荷字节流]</p>
     */
    public static ByteBuffer encode(byte[] payload) {
        // 分配空间:4 字节长度头 + 载荷本身
        ByteBuffer buf = ByteBuffer.allocate(LENGTH_BYTES + payload.length);
        // 写入长度前缀:putInt 把 payload.length 作为 4 字节大端 int 写入,这就是帧头的"长度前缀"
        buf.putInt(payload.length);
        // 写入真正的业务数据
        buf.put(payload);
        // flip:写→读模式切换,把"写指针位置"冻结成 limit,position 归零,
        // 这样调用方拿到 buf 后可直接 channel.write(buf) 从头读出全部数据
        buf.flip();
        return buf;
    }

    /**
     * 有状态的帧解码器:可多次 feed ByteBuffer,跨调用保留半包状态,正确处理粘包/半包。
     * 镜像 {@code GridNioServerBuffer.read()} 的"先凑齐长度、再凑齐载荷"状态机。
     *
     * <p><b>非线程安全</b>:内部持有 state、lenBuf、payloadBuf 等可变状态,无任何同步保护。
     * 多线程并发调用同一个 Decoder 实例会导致状态错乱、半包数据被污染。
     * 设计约束:一个 Decoder 实例绑定一个连接,由同一个线程独占调用
     * (NIO 模型下,一个 SocketChannel 的 read 天然只发生在一个 selector/worker 线程上)。</p>
     */
    public static final class Decoder {
        private static final int READING_LENGTH = 0;
        private static final int READING_PAYLOAD = 1;

        private int state = READING_LENGTH;
        private final ByteBuffer lenBuf = ByteBuffer.allocate(LENGTH_BYTES);
        private int payloadLen = -1;
        private ByteBuffer payloadBuf = null;

        /**
         * 喂入若干字节,返回本次解出的完整消息(0~N 条)。
         * 依赖下方的 copy 完成字节搬运,支持跨调用保留半包状态。
         */
        public List<byte[]> decode(ByteBuffer in) {
            // 收集本次解出的完整消息(可能 0 条=半包还没凑齐,也可能 N 条=粘包一次来多条)
            List<byte[]> out = new ArrayList<>();
            // 外层循环:把入参 in 里的字节全部消费完(position 追上 limit)
            while (in.hasRemaining()) {
                // ===== 阶段一:读长度头(4 字节 int) =====
                // 可能跨多次 read 调用才凑齐:第一次来 1 字节、第二次来 3 字节……
                if (state == READING_LENGTH) {
                    // 把 in 里的字节搬到 lenBuf,只搬双方都能接受的量
                    copy(in, lenBuf);
                    // lenBuf 写满 4 字节 → 长度头凑齐
                    if (!lenBuf.hasRemaining()) {
                        // flip 切到读模式,准备 getInt 读出长度值
                        lenBuf.flip();
                        // 读出 4 字节大端 int,即后续载荷的字节数
                        payloadLen = lenBuf.getInt();
                        if (payloadLen < 0) {
                            throw new IllegalStateException("negative frame length: " + payloadLen);
                        }
                        // 按长度预分配载荷缓冲区
                        payloadBuf = ByteBuffer.allocate(payloadLen);
                        // 切到阶段二,准备接收载荷
                        state = READING_PAYLOAD;
                    }
                }
                // ===== 阶段二:读载荷(payloadLen 字节) =====
                // 同样可能跨多次 read 凑齐;若 in 里还有剩余且本帧已切到 READING_PAYLOAD,继续搬
                if (state == READING_PAYLOAD) {
                    copy(in, payloadBuf);
                    // payloadBuf 写满 → 一条完整消息凑齐
                    if (!payloadBuf.hasRemaining()) {
                        // 直接取底层 array() 作为消息输出(无需 copy,因为 buffer 不会被复用)
                        out.add(payloadBuf.array());
                        // 重置状态机,回到阶段一等下一帧(处理粘包:in 里可能还有下一帧的数据)
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

        /**
         * 自适应字节搬运:把 src 剩余字节拷到 dst 剩余空间,每次只搬双方都能接受的量。
         *
         * <p>双路径设计:</p>
         * <ul>
         *   <li>快速路径(堆内 buffer):用 System.arraycopy 一次批量拷贝,memorymove 级别性能</li>
         *   <li>慢速路径(direct buffer):堆外内存无 array(),只能逐字节 get/put</li>
         * </ul>
         * <p>取 min(src.remaining, dst.remaining) 是半包处理的关键:
         * 一次 read 可能只来部分数据,先搬一部分,等下次凑齐。</p>
         */
        private static void copy(ByteBuffer src, ByteBuffer dst) {
            // 本次最多能搬的字节数:源可读量与目标可写量的较小值
            int n = Math.min(src.remaining(), dst.remaining());
            if (n <= 0) {
                return;
            }
            if (src.hasArray() && dst.hasArray()) {
                // 快速路径:两个都是堆内 buffer,用 native 的 System.arraycopy 一次搬完
                // arrayOffset + position 算出真实数组偏移(buffer 可能是大数组的切片)
                System.arraycopy(src.array(), src.arrayOffset() + src.position(),
                        dst.array(), dst.arrayOffset() + dst.position(), n);
                src.position(src.position() + n);
                dst.position(dst.position() + n);
            } else {
                // 慢速路径:至少有一个是 direct buffer(堆外内存,hasArray() 返回 false),
                // 只能逐字节 get/put,每次经过 Unsafe/native 调用
                for (int i = 0; i < n; i++) {
                    dst.put(src.get());
                }
            }
        }
    }
}
