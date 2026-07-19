package org.apache.ignite.learning.internal.pagemem.impl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import sun.misc.Unsafe;

/**
 * {@code sun.misc.Unsafe} 的薄包装(镜像 Ignite {@code modules/unsafe/.../GridUnsafe})。
 *
 * <p>把裸 Unsafe 用法收敛到一处:堆外 allocate/free + 按地址读写 + 裸指针包成 {@link ByteBuffer}。
 *
 * <ul>
 *   <li>{@code sun.misc.Unsafe} 在 JDK 17/21 位于 {@code jdk.unsupported} 模块,默认对 unnamed module
 *       可读 —— {@code allocateMemory/putLong/getLong} 等无需 {@code --add-exports}。</li>
 *   <li>{@link #wrapPointer(long, int)} 反射 {@code java.nio.DirectByteBuffer} 的包私有 / private 构造器
 *       (镜像 {@code GridUnsafe.createAndTestNewDirectBufferCtor}):用 {@code ByteBuffer.allocateDirect(1).getClass()}
 *       拿运行时类,JDK &lt;21 找 {@code (long,int)},JDK &gt;=21 找 {@code (long,long)}(private)。需 surefire
 *       {@code --add-opens java.base/java.nio=ALL-UNNAMED}。</li>
 * </ul>
 *
 * <p>后续若要避开内部 API,可整体换成 FFM({@code java.lang.foreign.MemorySegment},纯标准 JDK),
 * 本类是切换点(S8 拍板用 Unsafe,保真优先)。
 */
public final class OffHeap {
    private static final Unsafe UNSAFE;

    private static final Constructor<?> DIRECT_BUF_CTOR;

    /** DirectByteBuffer 构造器 capacity 参数是否为 long(JDK >=21 为 true)。 */
    private static final boolean DIRECT_BUF_LONG_CAP;

    static {
        try {
            // Unsafe.getUnsafe() 对非 bootstrap caller 抛 SecurityException,改反射 theUnsafe(镜像 GridUnsafe)。
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe)f.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError("无法获取 sun.misc.Unsafe: " + e);
        }

        Constructor<?> ctor;
        try {
            // 用运行时类(非 Class.forName),镜像 Ignite createAndTestNewDirectBufferCtor。
            ByteBuffer sample = ByteBuffer.allocateDirect(1);
            Class<?> dbbCls = sample.getClass(); // java.nio.DirectByteBuffer
            try {
                ctor = dbbCls.getDeclaredConstructor(long.class, int.class); // JDK < 21
            } catch (NoSuchMethodException e) {
                ctor = dbbCls.getDeclaredConstructor(long.class, long.class); // JDK >= 21(private)
            }
            ctor.setAccessible(true);
            // 探测构造器可用(镜像 Ignite 的 ensure),成功即释放探测内存。
            long probePtr = UNSAFE.allocateMemory(1);
            Object probeCap = ctor.getParameterTypes()[1] == long.class ? (long)1 : 1;
            ByteBuffer probe = (ByteBuffer)ctor.newInstance(probePtr, probeCap);
            if (!probe.isDirect())
                throw new IllegalStateException("反射构造的 ByteBuffer 非 direct");
            UNSAFE.freeMemory(probePtr);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(
                "无法反射 java.nio.DirectByteBuffer 构造器: " + e
                    + "(需 --add-opens java.base/java.nio=ALL-UNNAMED)");
        }
        DIRECT_BUF_CTOR = ctor;
        DIRECT_BUF_LONG_CAP = ctor.getParameterTypes()[1] == long.class;
    }

    private OffHeap() {
        // No-op.
    }

    public static long allocateMemory(long bytes) {
        return UNSAFE.allocateMemory(bytes);
    }

    public static void freeMemory(long address) {
        UNSAFE.freeMemory(address);
    }

    public static void putLong(long address, long value) {
        UNSAFE.putLong(address, value);
    }

    public static long getLong(long address) {
        return UNSAFE.getLong(address);
    }

    public static void putByte(long address, byte value) {
        UNSAFE.putByte(address, value);
    }

    public static byte getByte(long address) {
        return UNSAFE.getByte(address);
    }

    public static void zeroMemory(long address, long bytes) {
        UNSAFE.setMemory(address, bytes, (byte)0);
    }

    public static ByteBuffer wrapPointer(long address, int size) {
        try {
            // cap 参数按构造器签名传 int 或 long(反射 newInstance 不自动 int→long 提升)。
            Object capArg = DIRECT_BUF_LONG_CAP ? (long)size : size;
            return ((ByteBuffer)DIRECT_BUF_CTOR.newInstance(address, capArg)).order(ByteOrder.nativeOrder());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("wrapPointer 失败(address=" + address + ", size=" + size + ')', e);
        }
    }
}
