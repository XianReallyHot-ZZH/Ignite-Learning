package org.apache.ignite.learning.internal.marshaller;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link MarshallerContext} 的进程内实现(镜像 {@code MarshallerContextImpl} 的**注册表**部分)。
 *
 * <p><b>刻意不做</b>(见 S07 讲义「与 Ignite 对照」):集群级 mapping transport(propose/accept)、
 * 文件存储({@code MarshallerMappingFileStore})、peer class loading、跨平台(.NET)。
 * 学习版就是一个 {@code ConcurrentHashMap<Integer,String>}(typeId→className)。
 *
 * <p>{@code registerClassName} 的 typeId 取 {@code className.hashCode()}(镜像 Ignite 的 {@code resolveTypeId});
 * 冲突(不同类同名 hashCode)罕见,学习版 last-wins(Ignite 抛 {@code DuplicateTypeIdException} 经集群协商解决)。
 */
public class MarshallerContextImpl implements MarshallerContext {

    private final ConcurrentMap<Integer, String> byTypeId = new ConcurrentHashMap<>();

    @Override
    public boolean registerClassName(int typeId, String className) {
        String prev = byTypeId.putIfAbsent(typeId, className);
        if (prev == null) {
            return true; // 新注册
        }
        if (!prev.equals(className)) {
            // 冲突:last-wins(Ignite 会抛 DuplicateTypeIdException;学习版直接覆盖,概率极低)
            byTypeId.put(typeId, className);
        }
        return false; // 已存在(同名或冲突覆盖)
    }

    @Override
    public String getClassName(int typeId) {
        return byTypeId.get(typeId);
    }

    @Override
    public Class<?> getClass(int typeId, ClassLoader clsLdr) throws ClassNotFoundException {
        String name = byTypeId.get(typeId);
        if (name == null) {
            throw new ClassNotFoundException("no class registered for typeId " + typeId);
        }
        return Class.forName(name, true, clsLdr);
    }

    @Override
    public boolean isSystemType(String className) {
        return className != null
                && (className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("org.apache.ignite."));
    }
}
