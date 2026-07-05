package org.apache.ignite.learning.internal.direct;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * {@link MessageReader} 的默认实现(镜像 {@code internal/direct/DirectMessageReader})。
 *
 * <p>读 buffer 由 {@link #setReadBuffer(ByteBuffer)} 绑定(调用方已读出 type、定位到字段起点)。
 * {@code lastRead} 在 v1 总为 true(buffer 完整)。嵌套消息的 state 栈语义同 {@link DirectMessageWriter}。
 *
 * <p><b>非线程安全</b>:每条消息的解码都用 fresh reader。
 */
public final class DirectMessageReader implements MessageReader {

    private final DirectByteBufferStream s = new DirectByteBufferStream();
    private final MessageFactory factory;
    private int state;
    private final Deque<Integer> stateStack = new ArrayDeque<>();
    private boolean lastRead = true;

    public DirectMessageReader(MessageFactory factory) {
        this.factory = factory;
    }

    /** 绑定读 buffer(position 已在 type 之后),重置 state。 */
    public void setReadBuffer(ByteBuffer buf) {
        s.setReadBuffer(buf);
        this.state = 0;
        stateStack.clear();
        this.lastRead = true;
    }

    @Override
    public byte readByte() {
        return s.readByte();
    }

    @Override
    public short readShort() {
        return s.readShort();
    }

    @Override
    public int readInt() {
        return s.readInt();
    }

    @Override
    public long readLong() {
        return s.readLong();
    }

    @Override
    public boolean readBoolean() {
        return s.readBoolean();
    }

    @Override
    public byte[] readByteArray() {
        return s.readByteArray();
    }

    @Override
    public int[] readIntArray() {
        return s.readIntArray();
    }

    @Override
    public long[] readLongArray() {
        return s.readLongArray();
    }

    @Override
    public String readString() {
        return s.readString();
    }

    @Override
    public UUID readUuid() {
        return s.readUuid();
    }

    @Override
    public Message readMessage() {
        short type = s.readShort();
        if (type == Short.MIN_VALUE) {
            return null; // null 标记
        }
        Message child = factory.create(type);
        beforeInnerMessageRead();
        child.readFrom(this); // 子消息字段
        afterInnerMessageRead(true);
        return child;
    }

    @Override
    public boolean isLastRead() {
        return lastRead;
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
    public void beforeInnerMessageRead() {
        stateStack.push(state);
        state = 0;
    }

    @Override
    public void afterInnerMessageRead(boolean finished) {
        state = stateStack.pop();
    }
}
