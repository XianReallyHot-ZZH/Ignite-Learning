package org.apache.ignite.learning.internal.marshaller;

/**
 * typeId ↔ className ↔ Class 的注册表契约(镜像 {@code MarshallerContext})。
 *
 * <p>Ignite 的实现是**集群级**的:节点间 propose/accept 一个 typeId→className 映射,使线格式可以只传
 * 4 字节 typeId 而非全类名(配合 peer class loading)。**学习版 v2 只取进程内语义**
 * (一个 {@code ConcurrentHashMap},无 transport / 无文件 / 无 peer class loading)。
 */
public interface MarshallerContext {

    /**
     * 注册 typeId → className。
     *
     * @return true 表示新注册;false 表示已存在(且可能映射到同名或冲突名)。
     */
    boolean registerClassName(int typeId, String className);

    /** 按 typeId 查类名(未注册返回 null)。 */
    String getClassName(int typeId);

    /** 按 typeId 解析 Class(经 {@code Class.forName(name, true, clsLdr)});未注册抛 {@link ClassNotFoundException}。 */
    Class<?> getClass(int typeId, ClassLoader clsLdr) throws ClassNotFoundException;

    /** 是否系统类型(JDK / Ignite 前缀;学习版用前缀判定)。 */
    boolean isSystemType(String className);
}
