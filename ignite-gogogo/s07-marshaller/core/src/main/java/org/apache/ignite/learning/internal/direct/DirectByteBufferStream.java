package org.apache.ignite.learning.internal.direct;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 字段字节引擎(镜像 {@code internal/direct/stream/DirectByteBufferStream})。
 *
 * <p>v1 刻意简化(详见 S06 讲义「与 Ignite 对照」):
 * <ol>
 *   <li><b>定宽原语</b> —— 不做 Ignite 的 base-128 varint + zigzag 编码 int/long
 *       (那让小数值省字节,是性能优化;v1 用 {@link ByteBuffer} 默认 BIG_ENDIAN 定宽);</li>
 *   <li><b>写侧自增长 buffer / 读侧外给 ByteBuffer</b>;</li>
 *   <li><b>不做跨 partial-read 的 resume</b> —— 无 {@code tmpArrOff}/{@code arrOff}/
 *       {@code uuidState} 等逐字段游标。由 NIO 层({@code FrameCodec})保证一次给到完整消息,
 *       v1 的 read 总一次过(故 {@code lastFinished} 总为 true)。</li>
 * </ol>
 *
 * <p><b>null 编码</b>:数组/String 写 {@code int -1} 长度;UUID 写 1 字节 present 标志。
 */
final class DirectByteBufferStream {

    /** 写侧自增长 buffer 的初始容量。 */
    private static final int WRITE_INIT_CAP = 256;

    private ByteBuffer buf;
    private boolean writing;

    /** 开始一次写:初始化空的自增长 buffer。 */
    void startWrite() {
        this.buf = ByteBuffer.allocate(WRITE_INIT_CAP);
        this.writing = true;
    }

    /** 绑定读 buffer(调用方提供的完整消息字节;position 由调用方设定,通常已跳过 type)。 */
    void setReadBuffer(ByteBuffer buf) {
        this.buf = buf;
        this.writing = false;
    }

    /** 取出写好的全部字节(写侧专用)。 */
    byte[] writtenBytes() {
        buf.flip();
        byte[] a = new byte[buf.remaining()];
        buf.get(a);
        return a;
    }

    // ---- 写(均返回 true;v1 buffer 自增长,永不"写半")----

    private void ensureCap(int n) {
        while (buf.remaining() < n) {
            grow();
        }
    }

    private void grow() {
        ByteBuffer b = ByteBuffer.allocate(buf.capacity() * 2);
        buf.flip();
        b.put(buf);
        buf = b;
    }

    boolean writeByte(byte v) {
        ensureCap(1);
        buf.put(v);
        return true;
    }

    boolean writeShort(short v) {
        ensureCap(2);
        buf.putShort(v);
        return true;
    }

    boolean writeInt(int v) {
        ensureCap(4);
        buf.putInt(v);
        return true;
    }

    boolean writeLong(long v) {
        ensureCap(8);
        buf.putLong(v);
        return true;
    }

    boolean writeBoolean(boolean v) {
        ensureCap(1);
        buf.put((byte) (v ? 1 : 0));
        return true;
    }

    boolean writeByteArray(byte[] v) {
        if (v == null) {
            writeInt(-1);
            return true;
        }
        writeInt(v.length);
        ensureCap(v.length);
        buf.put(v);
        return true;
    }

    boolean writeIntArray(int[] v) {
        if (v == null) {
            writeInt(-1);
            return true;
        }
        writeInt(v.length);
        ensureCap(v.length * Integer.BYTES);
        for (int x : v) {
            buf.putInt(x);
        }
        return true;
    }

    boolean writeLongArray(long[] v) {
        if (v == null) {
            writeInt(-1);
            return true;
        }
        writeInt(v.length);
        ensureCap(v.length * Long.BYTES);
        for (long x : v) {
            buf.putLong(x);
        }
        return true;
    }

    boolean writeString(String v) {
        if (v == null) {
            writeInt(-1);
            return true;
        }
        byte[] utf = v.getBytes(StandardCharsets.UTF_8);
        writeInt(utf.length);
        ensureCap(utf.length);
        buf.put(utf);
        return true;
    }

    boolean writeUuid(java.util.UUID v) {
        if (v == null) {
            writeBoolean(false);
            return true;
        }
        writeBoolean(true);
        ensureCap(16);
        buf.putLong(v.getMostSignificantBits());
        buf.putLong(v.getLeastSignificantBits());
        return true;
    }

    // ---- 读 ----

    byte readByte() {
        return buf.get();
    }

    short readShort() {
        return buf.getShort();
    }

    int readInt() {
        return buf.getInt();
    }

    long readLong() {
        return buf.getLong();
    }

    boolean readBoolean() {
        return buf.get() != 0;
    }

    byte[] readByteArray() {
        int len = readInt();
        if (len < 0) {
            return null;
        }
        byte[] a = new byte[len];
        buf.get(a);
        return a;
    }

    int[] readIntArray() {
        int len = readInt();
        if (len < 0) {
            return null;
        }
        int[] a = new int[len];
        for (int i = 0; i < len; i++) {
            a[i] = buf.getInt();
        }
        return a;
    }

    long[] readLongArray() {
        int len = readInt();
        if (len < 0) {
            return null;
        }
        long[] a = new long[len];
        for (int i = 0; i < len; i++) {
            a[i] = buf.getLong();
        }
        return a;
    }

    String readString() {
        int len = readInt();
        if (len < 0) {
            return null;
        }
        byte[] a = new byte[len];
        buf.get(a);
        return new String(a, StandardCharsets.UTF_8);
    }

    java.util.UUID readUuid() {
        if (!readBoolean()) {
            return null;
        }
        long most = buf.getLong();
        long least = buf.getLong();
        return new java.util.UUID(most, least);
    }
}
