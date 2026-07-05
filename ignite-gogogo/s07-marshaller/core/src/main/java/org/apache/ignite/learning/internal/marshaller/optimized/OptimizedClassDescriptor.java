package org.apache.ignite.learning.internal.marshaller.optimized;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 每个类的序列化元数据缓存(镜像 {@code OptimizedClassDescriptor},原 {@code OptimizedMetadata})。
 *
 * <p>预计算一次(并跨调用缓存在 {@code OptimizedMarshaller.clsMap} 里):该类全部**非 static、非 transient**
 * 字段(沿超类链收集),**按字段名排序** —— 让读写双方对字段顺序达成一致(即使私有字段声明顺序不同)。
 *
 * <p><b>与 Ignite 的差距</b>:Ignite 的 {@code FieldInfo} 缓存 {@code GridUnsafe.objectFieldOffset},
 * 字段读写直取偏移(免 {@code Field.get} 反射开销);学习版用 {@code Field.get/set}(反射)—— 更慢但无 Unsafe。
 * Ignite 还预计算 {@code type} 标签、{@code writeObject/readObject} 反射方法、SHA-1 {@code checksum};
 * 学习版裁剪这些(标签由 writer 按运行时类型分发,无需预计算)。
 */
final class OptimizedClassDescriptor {

    private final Class<?> cls;
    private final Field[] fields;

    OptimizedClassDescriptor(Class<?> cls) {
        this.cls = cls;
        this.fields = collectFields(cls);
        for (Field f : fields) {
            f.setAccessible(true); // 访问私有字段(一次性,免后续每次反射检查)
        }
    }

    /** 沿超类链收集非 static、非 transient 字段,按名排序(读写一致)。镜像 Ignite 的字段收集 + 排序。 */
    private static Field[] collectFields(Class<?> cls) {
        List<Field> all = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) {
                    continue;
                }
                all.add(f);
            }
        }
        all.sort((a, b) -> a.getName().compareTo(b.getName()));
        return all.toArray(new Field[0]);
    }

    /** 按顺序把每个字段值交给 writer(递归)。{@code Field.get} 会装箱基本类型 → writer 按运行时类型分发。 */
    void writeFields(Object obj, OptimizedObjectWriter w)
            throws ReflectiveOperationException, java.io.IOException {
        for (Field f : fields) {
            w.writeObject(f.get(obj));
        }
    }

    /** 按顺序读回每个字段并 set 回对象。{@code Field.set} 会自动拆箱到基本类型字段。 */
    void readFields(Object obj, OptimizedObjectReader r)
            throws ReflectiveOperationException, java.io.IOException, ClassNotFoundException {
        for (Field f : fields) {
            Object val = r.readObject();
            f.set(obj, val);
        }
    }

    /** 线程安全的描述符缓存查表(命中即返;miss 则建并放入)。writer/reader 共用同一个 clsMap。 */
    static OptimizedClassDescriptor descriptorFor(Map<Class<?>, OptimizedClassDescriptor> clsMap, Class<?> cls) {
        OptimizedClassDescriptor d = clsMap.get(cls);
        if (d == null) {
            d = new OptimizedClassDescriptor(cls);
            OptimizedClassDescriptor prev = clsMap.putIfAbsent(cls, d);
            if (prev != null) {
                d = prev; // 并发下别人先放入,用别人的
            }
        }
        return d;
    }

    @Override
    public String toString() {
        return "OptimizedClassDescriptor{" + cls.getName() + ", " + fields.length + " fields}";
    }
}
