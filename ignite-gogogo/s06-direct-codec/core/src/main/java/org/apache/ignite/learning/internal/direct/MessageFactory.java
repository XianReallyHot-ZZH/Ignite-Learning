package org.apache.ignite.learning.internal.direct;

import java.util.function.Supplier;

/**
 * directType ↔ Message 的注册表(镜像 {@code internal/managers/communication/IgniteMessageFactoryImpl})。
 *
 * <p><b>O(1) 数组下标查询</b>:short 范围 [-32768, 32767],加 {@link #OFFSET}(=32768)映射到
 * [0, 65535] 的数组下标,故负 type(如 Ignite 的 {@code NODE_ID_MSG_TYPE=-1}、{@code HANDSHAKE_MSG_TYPE=-3})
 * 也合法,且查询是数组直取、无反射、无 Map。
 *
 * <p><b>注册期可写,建成后只读</b>:镜像 Ignite 约束(建成后 {@code register} 抛 {@code IllegalStateException}),
 * 避免运行期竞态改注册表。{@link #create} 对未知 type 抛异常 —— 即"白名单":只有注册过的 type 能构造。
 */
public final class MessageFactory {

    /** short → 非负数组下标的偏移(= {@code -Short.MIN_VALUE} = 32768)。镜像 {@code IgniteMessageFactoryImpl.OFF}。 */
    static final int OFFSET = -Short.MIN_VALUE;
    private static final int ARR_SIZE = 1 << Short.SIZE; // 65536

    @SuppressWarnings("unchecked")
    private final Supplier<Message>[] suppliers = (Supplier<Message>[]) new Supplier<?>[ARR_SIZE];

    private boolean initialized;

    /**
     * 注册一个 type → 工厂。建成后(调过 {@link #initialized()})再调抛异常;
     * 同一 type 重复注册也抛异常(注册应在启动期一次性完成,不幂等)。
     */
    public void register(short directType, Supplier<Message> supplier) {
        if (initialized) {
            throw new IllegalStateException("MessageFactory already initialized; cannot register type " + directType);
        }
        int idx = directType + OFFSET;
        if (suppliers[idx] != null) {
            throw new IllegalStateException("directType " + directType + " already registered");
        }
        suppliers[idx] = supplier;
    }

    /** 标记注册完成,此后 {@code register} 抛异常(运行期只读)。 */
    public void initialized() {
        this.initialized = true;
    }

    /** 按 type 创建一个空消息实例(随后由 {@code readFrom} 填字段)。未知 type 抛异常(白名单)。 */
    public Message create(short directType) {
        Supplier<Message> supplier = suppliers[directType + OFFSET];
        if (supplier == null) {
            throw new IllegalStateException("Unknown message directType: " + directType);
        }
        return supplier.get();
    }
}
