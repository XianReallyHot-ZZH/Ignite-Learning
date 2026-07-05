package org.apache.ignite.learning.internal.marshaller.optimized;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.learning.internal.marshaller.MarshallerContext;

/**
 * Optimized 序列化的读引擎(镜像 {@code OptimizedObjectInputStream})。{@link OptimizedObjectWriter} 的逆。
 *
 * <p>按 type tag 分发重建值。{@code HANDLE} → 用读侧 handle 表({@code ArrayList<Object>})解析 back-ref
 * —— 该表按 DFS 前序填入,与 writer 的分配顺序一致,故下标对齐。
 *
 * <p>实例化用 {@link OptimizedMarshallerUtils#newInstance}(反射无参构造器;Ignite 用 Unsafe.allocateInstance)。
 * 读 {@code OBJECT}/{@code ARRAY} 时**先建实例 + 占 handle,再读字段/元素** —— 这样新读到的环引用能解析到正在构建的实例。
 *
 * <p><b>非线程安全</b>:每条消息的 unmarshal 新建一个 reader。
 */
final class OptimizedObjectReader {

    private final DataInputStream in;
    private final Map<Class<?>, OptimizedClassDescriptor> clsMap;
    private final MarshallerContext ctx;
    private final ClassLoader clsLdr;

    /** handle 下标 → 对象(writer 的 objHandles 的逆;DFS 前序填入)。 */
    private final List<Object> objects = new ArrayList<>();
    /** class handle 下标 → 类。 */
    private final List<Class<?>> classes = new ArrayList<>();

    OptimizedObjectReader(InputStream in, Map<Class<?>, OptimizedClassDescriptor> clsMap,
                          MarshallerContext ctx, ClassLoader clsLdr) {
        this.in = new DataInputStream(in);
        this.clsMap = clsMap;
        this.ctx = ctx;
        this.clsLdr = clsLdr != null ? clsLdr : Thread.currentThread().getContextClassLoader();
    }

    /** 反序列化的递归入口。 */
    Object readObject() throws IOException, ClassNotFoundException {
        byte tag = in.readByte();
        switch (tag) {
            case OptimizedMarshallerUtils.NULL:
                return null;
            case OptimizedMarshallerUtils.HANDLE:
                return objects.get(in.readInt());
            case OptimizedMarshallerUtils.BYTE:
                return in.readByte();
            case OptimizedMarshallerUtils.SHORT:
                return in.readShort();
            case OptimizedMarshallerUtils.INT:
                return in.readInt();
            case OptimizedMarshallerUtils.LONG:
                return in.readLong();
            case OptimizedMarshallerUtils.BOOLEAN:
                return in.readBoolean();
            case OptimizedMarshallerUtils.CHAR:
                return in.readChar();
            case OptimizedMarshallerUtils.FLOAT:
                return in.readFloat();
            case OptimizedMarshallerUtils.DOUBLE:
                return in.readDouble();
            case OptimizedMarshallerUtils.STRING:
                return in.readUTF();
            case OptimizedMarshallerUtils.UUID_TAG:
                return new UUID(in.readLong(), in.readLong());
            case OptimizedMarshallerUtils.BYTE_ARRAY: {
                int len = in.readInt();
                byte[] a = new byte[len];
                in.readFully(a);
                return a;
            }
            case OptimizedMarshallerUtils.ARRAY:
                return readArray();
            case OptimizedMarshallerUtils.OBJECT:
                return readOrdinary();
            default:
                throw new IOException("unknown type tag: " + tag);
        }
    }

    private Object readArray() throws IOException, ClassNotFoundException {
        Class<?> c = readClass();
        int len = in.readInt();
        Object arr = Array.newInstance(c.getComponentType(), len);
        assignObjHandle(arr); // 与 writer 同序:分配 handle 在元素之前
        Class<?> comp = c.getComponentType();
        if (comp.isPrimitive()) {
            for (int i = 0; i < len; i++) {
                Array.set(arr, i, readPrimitiveElement(comp));
            }
        } else {
            for (int i = 0; i < len; i++) {
                Array.set(arr, i, readObject());
            }
        }
        return arr;
    }

    private Object readPrimitiveElement(Class<?> comp) throws IOException {
        if (comp == int.class) {
            return in.readInt();
        }
        if (comp == long.class) {
            return in.readLong();
        }
        if (comp == boolean.class) {
            return in.readBoolean();
        }
        if (comp == short.class) {
            return in.readShort();
        }
        if (comp == char.class) {
            return in.readChar();
        }
        if (comp == float.class) {
            return in.readFloat();
        }
        if (comp == double.class) {
            return in.readDouble();
        }
        throw new IOException("unsupported primitive array component: " + comp);
    }

    private Object readOrdinary() throws IOException, ClassNotFoundException {
        try {
            Class<?> c = readClass();
            OptimizedClassDescriptor desc = OptimizedClassDescriptor.descriptorFor(clsMap, c);
            Object obj = OptimizedMarshallerUtils.newInstance(c); // 反射无参构造器(Ignite 用 Unsafe)
            assignObjHandle(obj); // 与 writer 同序:先占 handle 再读字段(环引用可解析到此实例)
            desc.readFields(obj, this);
            return obj;
        } catch (ReflectiveOperationException e) {
            throw new IOException("deserialization failed", e);
        }
    }

    private Class<?> readClass() throws IOException, ClassNotFoundException {
        boolean isNew = in.readBoolean();
        if (isNew) {
            String name = in.readUTF();
            Class<?> c = Class.forName(name, true, clsLdr);
            classes.add(c); // 占下一个 class handle 下标(与 writer 的 classHandles 同序)
            if (ctx != null) {
                ctx.registerClassName(name.hashCode(), name);
            }
            return c;
        }
        return classes.get(in.readInt());
    }

    private void assignObjHandle(Object o) {
        objects.add(o); // 下标 = 当前 size,与 writer 的 seq 同序
    }
}
