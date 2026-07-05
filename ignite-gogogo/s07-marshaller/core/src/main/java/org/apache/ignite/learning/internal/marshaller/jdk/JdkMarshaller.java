package org.apache.ignite.learning.internal.marshaller.jdk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import org.apache.ignite.learning.IgniteCheckedException;
import org.apache.ignite.learning.internal.marshaller.AbstractMarshaller;
import org.apache.ignite.learning.internal.marshaller.MarshallerContextImpl;

/**
 * {@link org.apache.ignite.learning.internal.marshaller.Marshaller} 的 JDK 实现包一层
 * (镜像 {@code JdkMarshallerImpl}):字面 {@code new ObjectOutputStream} + {@code writeObject},及逆过程。
 *
 * <p>用途:① 与 {@code OptimizedMarshaller} **体积对照**(体积对比测试用它当"Java 序列化"基准);
 * ② 兜底(Optimized 处理不了的类型可回退;学习版未做自动回退)。
 *
 * <p><b>简化</b>:忽略 {@code clsLdr}(用 {@code ObjectInputStream} 默认的 {@code resolveClass});
 * Ignite 的 {@code JdkMarshallerObjectInputStream} 会经 {@code MarshallerExclusions} 过滤,学习版不做。
 */
public final class JdkMarshaller extends AbstractMarshaller {

    public JdkMarshaller() {
        this.ctx = new MarshallerContextImpl();
    }

    @Override
    public void marshal(Object obj, OutputStream out) throws IgniteCheckedException {
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(obj);
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
        try (ObjectInputStream ois = new ObjectInputStream(in)) {
            return (T) ois.readObject();
        } catch (Exception e) {
            throw new IgniteCheckedException("Failed to deserialize object", e);
        }
    }
}
