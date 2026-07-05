package org.apache.ignite.learning.internal.marshaller.optimized;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.learning.internal.marshaller.MarshallerContext;

/**
 * Optimized 序列化的写引擎(镜像 {@code OptimizedObjectOutputStream})。
 *
 * <p>每个值前缀 1 字节 type tag;按运行时类型分发(boxed 原语→原语 tag、String/UUID/byte[]→专用 tag、
 * 其它数组→{@code ARRAY}、普通对象→{@code OBJECT})。
 *
 * <p><b>环/重复检测</b>:每写一个可变图节点(对象/数组)**在写其字段之前**分配一个 int handle
 * (经 {@code IdentityHashMap});再次遇到同一对象 → 写 {@code HANDLE}+下标(Ignite 用 {@code GridHandleTable},
 * 语义一致)。boxed 原语/String/UUID 是不可变的,不参与环,不分配 handle。
 *
 * <p><b>类去重</b>:每流一个 class-handle 表(首次写全类名 + 注册到 context,之后写 handle)。
 *
 * <p><b>非线程安全</b>:每条消息的 marshal 新建一个 writer。
 */
final class OptimizedObjectWriter {

    private final DataOutputStream out;
    private final Map<Class<?>, OptimizedClassDescriptor> clsMap;
    private final MarshallerContext ctx;
    private final boolean requireSerializable;

    /** 对象 → handle 下标(身份语义,仅对可变节点:OBJECT/ARRAY)。 */
    private final IdentityHashMap<Object, Integer> objHandles = new IdentityHashMap<>();
    private int objHandleSeq = 0;
    /** 类 → handle 下标(每流去重)。 */
    private final IdentityHashMap<Class<?>, Integer> classHandles = new IdentityHashMap<>();
    private int classHandleSeq = 0;

    OptimizedObjectWriter(OutputStream out, Map<Class<?>, OptimizedClassDescriptor> clsMap,
                          MarshallerContext ctx, boolean requireSerializable) {
        this.out = new DataOutputStream(out);
        this.clsMap = clsMap;
        this.ctx = ctx;
        this.requireSerializable = requireSerializable;
    }

    /** 序列化的递归入口。 */
    void writeObject(Object o) throws IOException {
        try {
            if (o == null) {
                out.writeByte(OptimizedMarshallerUtils.NULL);
                return;
            }
            Integer h = objHandles.get(o); // 已写过这个可变节点?→ back-ref
            if (h != null) {
                out.writeByte(OptimizedMarshallerUtils.HANDLE);
                out.writeInt(h);
                return;
            }
            writeNonNull(o);
        } catch (ReflectiveOperationException e) {
            throw new IOException("serialization failed for " + o, e);
        }
    }

    private void writeNonNull(Object o) throws IOException, ReflectiveOperationException {
        Class<?> c = o.getClass();
        // boxed 原语 → 原语 tag(紧凑,免 OBJECT 包裹)
        if (c == Byte.class) {
            out.writeByte(OptimizedMarshallerUtils.BYTE);
            out.writeByte((Byte) o);
        } else if (c == Short.class) {
            out.writeByte(OptimizedMarshallerUtils.SHORT);
            out.writeShort((Short) o);
        } else if (c == Integer.class) {
            out.writeByte(OptimizedMarshallerUtils.INT);
            out.writeInt((Integer) o);
        } else if (c == Long.class) {
            out.writeByte(OptimizedMarshallerUtils.LONG);
            out.writeLong((Long) o);
        } else if (c == Boolean.class) {
            out.writeByte(OptimizedMarshallerUtils.BOOLEAN);
            out.writeBoolean((Boolean) o);
        } else if (c == Character.class) {
            out.writeByte(OptimizedMarshallerUtils.CHAR);
            out.writeChar((Character) o);
        } else if (c == Float.class) {
            out.writeByte(OptimizedMarshallerUtils.FLOAT);
            out.writeFloat((Float) o);
        } else if (c == Double.class) {
            out.writeByte(OptimizedMarshallerUtils.DOUBLE);
            out.writeDouble((Double) o);
        } else if (c == String.class) {
            out.writeByte(OptimizedMarshallerUtils.STRING);
            out.writeUTF((String) o);
        } else if (c == UUID.class) {
            out.writeByte(OptimizedMarshallerUtils.UUID_TAG);
            UUID u = (UUID) o;
            out.writeLong(u.getMostSignificantBits());
            out.writeLong(u.getLeastSignificantBits());
        } else if (c == byte[].class) {
            out.writeByte(OptimizedMarshallerUtils.BYTE_ARRAY); // 直接载荷(Direct 信封的常用形式)
            byte[] a = (byte[]) o;
            out.writeInt(a.length);
            out.write(a);
        } else if (c.isArray()) {
            writeArray(o, c);
        } else {
            writeOrdinary(o, c);
        }
    }

    /** 非 byte[] 的数组:写数组类 + handle + 长度 + 元素(原语数组逐元素,引用数组递归)。 */
    private void writeArray(Object arr, Class<?> c) throws IOException, ReflectiveOperationException {
        out.writeByte(OptimizedMarshallerUtils.ARRAY);
        writeClass(c);
        int len = Array.getLength(arr);
        assignObjHandle(arr); // 数组也是可变图节点,先分配 handle 再写元素(环检测)
        out.writeInt(len);
        Class<?> comp = c.getComponentType();
        if (comp.isPrimitive()) {
            for (int i = 0; i < len; i++) {
                writePrimitiveElement(comp, Array.get(arr, i));
            }
        } else {
            for (int i = 0; i < len; i++) {
                writeObject(Array.get(arr, i));
            }
        }
    }

    private void writePrimitiveElement(Class<?> comp, Object boxed) throws IOException {
        if (comp == int.class) {
            out.writeInt((Integer) boxed);
        } else if (comp == long.class) {
            out.writeLong((Long) boxed);
        } else if (comp == boolean.class) {
            out.writeBoolean((Boolean) boxed);
        } else if (comp == short.class) {
            out.writeShort((Short) boxed);
        } else if (comp == char.class) {
            out.writeChar((Character) boxed);
        } else if (comp == float.class) {
            out.writeFloat((Float) boxed);
        } else if (comp == double.class) {
            out.writeDouble((Double) boxed);
        } else {
            throw new IOException("unsupported primitive array component: " + comp);
        }
    }

    /** 普通对象:校验 Serializable → 写类描述 → 分配 handle → 写各字段(递归)。 */
    private void writeOrdinary(Object o, Class<?> c) throws IOException, ReflectiveOperationException {
        if (requireSerializable && !java.io.Serializable.class.isAssignableFrom(c)) {
            throw new IOException("class " + c.getName()
                    + " is not Serializable (setRequireSerializable(false) to allow; Ignite uses Unsafe to bypass)");
        }
        out.writeByte(OptimizedMarshallerUtils.OBJECT);
        writeClass(c);
        OptimizedClassDescriptor desc = OptimizedClassDescriptor.descriptorFor(clsMap, c);
        assignObjHandle(o); // 关键:写字段前先占 handle,这样自引用/环能被 HANDLE 命中
        desc.writeFields(o, this);
    }

    /** 类描述:首次写全类名 + 注册到 context;之后写 handle(每流去重)。 */
    private void writeClass(Class<?> c) throws IOException {
        Integer ch = classHandles.get(c);
        if (ch != null) {
            out.writeBoolean(false); // handle 跟随
            out.writeInt(ch);
        } else {
            out.writeBoolean(true); // 新类:全类名跟随
            out.writeUTF(c.getName());
            classHandles.put(c, classHandleSeq++);
            if (ctx != null) {
                ctx.registerClassName(c.getName().hashCode(), c.getName()); // 镜像 Ignite 注册流
            }
        }
    }

    private int assignObjHandle(Object o) {
        int id = objHandleSeq++;
        objHandles.put(o, id);
        return id;
    }
}
