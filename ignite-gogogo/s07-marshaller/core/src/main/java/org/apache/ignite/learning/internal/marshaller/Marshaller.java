package org.apache.ignite.learning.internal.marshaller;

import java.io.InputStream;
import java.io.OutputStream;
import org.apache.ignite.learning.IgniteCheckedException;

/**
 * 任意对象 ↔ 字节的序列化 SPI(镜像 {@code modules/binary/api/.../marshaller/Marshaller.java})。
 *
 * <p><b>与 Direct 编解码的分工</b>(P02 §3.3):Direct 处理**固定协议消息**({@code Message} 接口,
 * 字段编译期已知);Marshaller 处理**任意用户对象**(运行期才知形状)。二者在通信层以 {@code byte[]} seam 汇合:
 * Marshaller 把用户对象编成 {@code byte[]},作为 Direct 信封的一个 {@code writeByteArray} 字段传输。
 *
 * <p>所有方法抛 {@link IgniteCheckedException}(镜像 Ignite)。
 */
public interface Marshaller {

    /** 把对象序列化写入输出流。 */
    void marshal(Object obj, OutputStream out) throws IgniteCheckedException;

    /** 把对象序列化为 byte[](载荷层编码,随后可塞进 Direct 信封字段)。 */
    byte[] marshal(Object obj) throws IgniteCheckedException;

    /** 从 byte[] 反序列化对象({@code clsLdr} 用于加载类,学习版 JdkMarshaller 简化忽略)。 */
    <T> T unmarshal(byte[] bytes, ClassLoader clsLdr) throws IgniteCheckedException;

    /** 从输入流反序列化对象。 */
    <T> T unmarshal(InputStream in, ClassLoader clsLdr) throws IgniteCheckedException;
}
