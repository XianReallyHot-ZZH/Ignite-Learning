package org.apache.ignite.learning.internal.direct;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * {@link MessageWriter} 的默认实现(镜像 {@code internal/direct/DirectMessageWriter})。
 *
 * <p>薄 facade:字段字节读写委托 {@link DirectByteBufferStream};额外维护
 * <ul>
 *   <li>{@code state} 字段游标(消息的 {@code switch(state)} 用);</li>
 *   <li>嵌套消息读写的 state 栈:{@code beforeInnerMessageWrite} 压栈、{@code afterInnerMessageWrite} 弹栈
 *       —— 子消息要独占 state=0 开始,故进/出子消息时保存/恢复父 state。</li>
 * </ul>
 *
 * <p><b>非线程安全</b>:每条消息的编码都用 fresh writer(由 {@code MessageCodecFilter} 每消息新建)。
 */
public final class DirectMessageWriter implements MessageWriter {

    private final DirectByteBufferStream s = new DirectByteBufferStream();
    private int state;
    private final Deque<Integer> stateStack = new ArrayDeque<>();

    /** 开始一次写:重置 stream 与 state。 */
    public void startWrite() {
        s.startWrite();
        this.state = 0;
        stateStack.clear();
    }

    /** 取出写好的全部字节(调用方负责随后把它交给 outbound 链)。 */
    public byte[] writtenBytes() {
        return s.writtenBytes();
    }

    @Override
    public boolean writeByte(byte v) {
        return s.writeByte(v);
    }

    @Override
    public boolean writeShort(short v) {
        return s.writeShort(v);
    }

    @Override
    public boolean writeInt(int v) {
        return s.writeInt(v);
    }

    @Override
    public boolean writeLong(long v) {
        return s.writeLong(v);
    }

    @Override
    public boolean writeBoolean(boolean v) {
        return s.writeBoolean(v);
    }

    @Override
    public boolean writeByteArray(byte[] v) {
        return s.writeByteArray(v);
    }

    @Override
    public boolean writeIntArray(int[] v) {
        return s.writeIntArray(v);
    }

    @Override
    public boolean writeLongArray(long[] v) {
        return s.writeLongArray(v);
    }

    @Override
    public boolean writeString(String v) {
        return s.writeString(v);
    }

    @Override
    public boolean writeUuid(UUID v) {
        return s.writeUuid(v);
    }

    @Override
    public boolean writeMessage(Message m) {
        if (m == null) {
            return s.writeShort(Short.MIN_VALUE); // null 标记
        }
        s.writeShort(m.directType()); // 子消息 type
        beforeInnerMessageWrite();
        boolean finished = m.writeTo(this); // 子消息字段
        afterInnerMessageWrite(finished);
        return finished;
    }

    @Override
    public int state() {
        return state;
    }

    @Override
    public void incrementState() {
        state++;
    }

    @Override
    public void beforeInnerMessageWrite() {
        stateStack.push(state);
        state = 0;
    }

    @Override
    public void afterInnerMessageWrite(boolean finished) {
        state = stateStack.pop();
    }
}
