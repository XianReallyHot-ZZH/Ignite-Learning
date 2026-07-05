package org.apache.ignite.learning.internal.direct;

/**
 * 所有协议消息的根接口(镜像 {@code plugin/extensions/communication/Message})。
 *
 * <p>每条消息声明一个 2 字节 {@code directType}(类型注册表键),并手写
 * {@link #writeTo}/{@link #readFrom} 按固定字段顺序、自描述地读写。与 Java 序列化的根本区别:
 * 无线上类描述符、无反射 —— 字段顺序与类型在编译期固定,故更紧凑更快也更安全(白名单)。
 *
 * <p><b>v1 wire 格式</b>(由 {@link DirectMessageWriter}/{@link DirectMessageReader} 编解码):
 * 一条消息在线上 = {@code [2 字节 directType][字段1][字段2]…}。{@code writeTo}/{@code readFrom}
 * <b>只处理字段</b>,不处理 type —— type 由上层写一次:顶层消息由 {@code MessageCodecFilter}
 * 写,嵌套消息由 {@code MessageWriter#writeMessage} / {@code MessageReader#readMessage} 写。
 *
 * <p><b>注</b>:Ignite 2.18.0 的 {@code Message.writeTo/readFrom} 是 {@code @Deprecated} 兜底方法
 * (新消息走 codegen {@code MessageSerializer});学习版 v1 直接手写这两个方法(非 deprecated)。
 */
public interface Message {

    /** directType 占用的字节数(线上 type 字段长度)。镜像 {@code Message.DIRECT_TYPE_SIZE}。 */
    int DIRECT_TYPE_SIZE = 2;

    /** 本消息的类型 ID(类型注册表键,2 字节 short,可为负)。 */
    short directType();

    /**
     * 把本消息的字段(不含 type)按固定顺序写入 writer。
     *
     * @return 是否一次写完(v1 buffer 始终完整,总返回 true;Ignite 在 partial-write 时返回 false)
     */
    boolean writeTo(MessageWriter w);

    /**
     * 从 reader 按固定顺序读回本消息的字段(不含 type,调用方已消费 type 并建好实例)。
     *
     * @return 是否一次读完(v1 总返回 true)
     */
    boolean readFrom(MessageReader r);
}
