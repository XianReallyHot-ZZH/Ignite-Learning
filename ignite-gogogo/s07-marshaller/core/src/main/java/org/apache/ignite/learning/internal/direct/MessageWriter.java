package org.apache.ignite.learning.internal.direct;

import java.util.UUID;

/**
 * 消息字段写侧契约(镜像 {@code plugin/extensions/communication/MessageWriter})。
 *
 * <p>每个 {@code writeXxx} 返回 {@code lastFinished}:v1 buffer 始终完整故总返回 true;
 * Ignite 在 buffer 写满时返回 false,由消息的 {@code switch(state)} 状态机跨调用续写。
 *
 * <p>{@code state}/{@code incrementState} 是消息内字段游标(消息的 {@code switch(state)} 用);
 * {@code beforeInnerMessageWrite}/{@code afterInnerMessageWrite} 是嵌套消息读写时
 * 切换字段游标的钩子(子消息要独占 state=0 开始)。
 */
public interface MessageWriter {

    boolean writeByte(byte v);

    boolean writeShort(short v);

    boolean writeInt(int v);

    boolean writeLong(long v);

    boolean writeBoolean(boolean v);

    boolean writeByteArray(byte[] v);

    boolean writeIntArray(int[] v);

    boolean writeLongArray(long[] v);

    boolean writeString(String v);

    boolean writeUuid(UUID v);

    /** 嵌套消息:null 编码为 {@code Short.MIN_VALUE};否则写子消息 directType + 子消息字段。 */
    boolean writeMessage(Message v);

    /** 当前字段游标(消息内的 switch(state) 用)。 */
    int state();

    /** 推进到下一字段(case 贯穿模式里成功写完一字段后调)。 */
    void incrementState();

    /** 进入嵌套消息写前:保存当前 state,置 0 给子消息。 */
    void beforeInnerMessageWrite();

    /** 嵌套消息写完:恢复父 state。 */
    void afterInnerMessageWrite(boolean finished);
}
