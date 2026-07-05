package org.apache.ignite.learning.internal.marshaller;

/**
 * {@link Marshaller} 的基类(镜像 {@code AbstractMarshaller}):仅持共享的 {@link MarshallerContext}。
 *
 * <p>类名→typeId 的元数据缓存不在此(Ignite 把它放在 {@code OptimizedMarshallerImpl.clsMap});
 * 本学习版同样由具体实现(如 {@code OptimizedMarshaller})自持类描述符缓存。
 */
public abstract class AbstractMarshaller implements Marshaller {

    /** 进程内类注册表;具体实现构造时默认建一个 {@link MarshallerContextImpl}。 */
    protected MarshallerContext ctx;

    /** 注入 context(供后续注入集群级 context 的扩展点)。 */
    public void setContext(MarshallerContext ctx) {
        this.ctx = ctx;
    }

    /** 取 context(供读写引擎注册/查询类)。 */
    protected MarshallerContext context() {
        return ctx;
    }
}
