package org.apache.ignite.learning.internal.direct;

import java.util.UUID;

/**
 * 消息字段读侧契约(镜像 {@code plugin/extensions/communication/MessageReader})。
 * 读出一个值后用 {@link #isLastRead()} 判断是否完整(v1 总为 true)。
 */
public interface MessageReader {

    byte readByte();

    short readShort();

    int readInt();

    long readLong();

    boolean readBoolean();

    byte[] readByteArray();

    int[] readIntArray();

    long[] readLongArray();

    String readString();

    UUID readUuid();

    /** 嵌套消息:读 directType(Short.MIN_VALUE=null)→ 建实例 → 读字段;返回 null 或 Message。 */
    Message readMessage();

    /** 上次 readXxx 是否完整读完(v1 总 true;Ignite 在 buffer 不足时 false 触发续读)。 */
    boolean isLastRead();

    /** 当前字段游标(消息内的 switch(state) 用)。 */
    int state();

    /** 推进到下一字段。 */
    void incrementState();

    /** 进入嵌套消息读前:保存当前 state,置 0 给子消息。 */
    void beforeInnerMessageRead();

    /** 嵌套消息读完:恢复父 state。 */
    void afterInnerMessageRead(boolean finished);
}
