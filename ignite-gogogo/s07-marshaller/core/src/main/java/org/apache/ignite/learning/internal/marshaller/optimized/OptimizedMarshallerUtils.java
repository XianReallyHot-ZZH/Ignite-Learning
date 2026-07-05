package org.apache.ignite.learning.internal.marshaller.optimized;

import java.lang.reflect.Constructor;

/**
 * Optimized marshaller 的类型标签常量 + 小工具(镜像 {@code OptimizedMarshallerUtils})。
 *
 * <p>线格式:每个值前缀 1 字节 type tag,然后是 tag 决定的载荷。
 * 标签值(0..14)v1 自选(Ignite 用 0..29 + 100..102,含 EXTERNALIZABLE/PROXY 等,本学习版裁剪)。
 */
final class OptimizedMarshallerUtils {

    private OptimizedMarshallerUtils() {
    }

    static final byte NULL = 0;        // 无载荷
    static final byte HANDLE = 1;      // int:对象 back-ref 的 handle 下标(环/重复)
    static final byte BYTE = 2;        // 1 字节
    static final byte SHORT = 3;       // 2 字节
    static final byte INT = 4;         // 4 字节
    static final byte LONG = 5;        // 8 字节
    static final byte BOOLEAN = 6;     // 1 字节
    static final byte CHAR = 7;        // 2 字节
    static final byte FLOAT = 8;       // 4 字节
    static final byte DOUBLE = 9;      // 8 字节
    static final byte STRING = 10;     // modified-UTF-8(DataOutputStream.writeUTF)
    static final byte UUID_TAG = 11;   // 16 字节(most + least)
    static final byte BYTE_ARRAY = 12; // int 长度 + 字节
    static final byte ARRAY = 13;      // 类描述(数组类)+ int 长度 + 元素
    static final byte OBJECT = 14;     // 类描述 + 各字段(递归)

    /**
     * 反射实例化:要求类本身有可访问的无参构造器。
     *
     * <p><b>与 Ignite 的差距</b>:Ignite 用 {@code sun.misc.Unsafe.allocateInstance} **绕过所有构造器**
     * 实例化(因为 Java 序列化"找首个非 Serializable 祖先的无参构造器"规则对 Ignite 内部类常不可满足)。
     * 学习版避开 Unsafe(危险),用反射 —— 故要求类有无参构造器(普通 POJO 都满足);无则抛异常。
     */
    static Object newInstance(Class<?> cls) throws ReflectiveOperationException {
        Constructor<?> c = cls.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }
}
