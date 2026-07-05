package org.apache.ignite.learning.internal.marshaller.optimized;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ignite.learning.IgniteCheckedException;
import org.apache.ignite.learning.internal.marshaller.AbstractMarshaller;
import org.apache.ignite.learning.internal.marshaller.MarshallerContextImpl;

/**
 * {@link org.apache.ignite.learning.internal.marshaller.Marshaller} 的 Optimized 实现
 * (镜像 {@code OptimizedMarshallerImpl})。
 *
 * <p>自定义紧凑二进制格式(type tag + 类描述去重 + handle 表环检测 + 反射字段),比 JDK 默认序列化更小更快:
 * 无流头(magic/version)、无每类完整字段签名、boxed 原语用原语 tag。号称约 20× 于 JDK(Ignite Javadoc)。
 *
 * <p>持有跨调用复用的 {@code clsMap}(类 → {@link OptimizedClassDescriptor} 元数据缓存)。
 * 每次 marshal/unmarshal 借一个 fresh writer/reader(持有 per-stream handle 表)。
 *
 * <p>{@code requireSerializable} 默认 true(非 Serializable 抛异常)—— 与 Ignite 一致的安全默认
 * (Ignite 能用 Unsafe 序列化非 Serializable,但那是 footgun,故默认关)。
 */
public final class OptimizedMarshaller extends AbstractMarshaller {

    /** 类 → 序列化元数据缓存(跨 marshal/unmarshal 调用复用,镜像 Ignite {@code OptimizedMarshallerImpl.clsMap})。 */
    private final Map<Class<?>, OptimizedClassDescriptor> clsMap = new ConcurrentHashMap<>();

    private volatile boolean requireSerializable = true;

    public OptimizedMarshaller() {
        this.ctx = new MarshallerContextImpl(); // 进程内默认 context
    }

    /** 是否强制被序列化对象实现 {@code Serializable}(默认 true)。 */
    public void setRequireSerializable(boolean requireSerializable) {
        this.requireSerializable = requireSerializable;
    }

    @Override
    public void marshal(Object obj, OutputStream out) throws IgniteCheckedException {
        try {
            new OptimizedObjectWriter(out, clsMap, ctx, requireSerializable).writeObject(obj);
        } catch (Exception e) {
            throw new IgniteCheckedException("Failed to serialize object: " + obj, e);
        }
    }

    @Override
    public byte[] marshal(Object obj) throws IgniteCheckedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        marshal(obj, baos);
        return baos.toByteArray();
    }

    @Override
    public <T> T unmarshal(byte[] bytes, ClassLoader clsLdr) throws IgniteCheckedException {
        return unmarshal(new ByteArrayInputStream(bytes), clsLdr);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unmarshal(InputStream in, ClassLoader clsLdr) throws IgniteCheckedException {
        try {
            return (T) new OptimizedObjectReader(in, clsMap, ctx, clsLdr).readObject();
        } catch (Exception e) {
            throw new IgniteCheckedException("Failed to deserialize object", e);
        }
    }
}
