package org.apache.ignite.learning.internal.direct;

import java.util.Arrays;
import java.util.Objects;

/**
 * S6 的 demo 消息 —— 镜像 {@code internal/managers/communication/GridIoMessage} 的手写
 * {@code writeTo}/{@code readFrom} 模式(照搬其 {@code switch(state)} + case 贯穿 + {@code incrementState})。
 *
 * <p>字段:{@code long id}、{@code String payload}、{@code byte[] data},按此顺序读写。
 * 这是 Ignite 每条协议消息的标准写法:字段顺序与类型在编译期固定,运行期只按序读写,无线上类描述符。
 *
 * <p><b>v1 注</b>:由于 buffer 始终完整,各 {@code writeXxx}/{@code readXxx} 总成功,
 * {@code switch(state)} 实际总一次走完;Ignite 在 partial-read/write 时才会出现"某 case 返回 false
 * 跳出、下次同 state 续读"的 resume 场景(v1 由 {@code FrameCodec} 保证完整,故不触发)。
 */
public final class PingMessage implements Message {

    /** PingMessage 的 directType(学习版自选;避开 Ignite 保留的负 type 与 {@code Short.MIN_VALUE} null 标记)。 */
    public static final short TYPE = 1;

    public long id;
    public String payload;
    public byte[] data;

    public PingMessage() {
        // 读侧:工厂 create 出空实例后 readFrom 填字段
    }

    public PingMessage(long id, String payload, byte[] data) {
        this.id = id;
        this.payload = payload;
        this.data = data;
    }

    @Override
    public short directType() {
        return TYPE;
    }

    @Override
    public boolean writeTo(MessageWriter w) {
        switch (w.state()) {
            case 0:
                if (!w.writeLong(id)) {
                    return false;
                }
                w.incrementState();
            case 1:
                if (!w.writeString(payload)) {
                    return false;
                }
                w.incrementState();
            case 2:
                if (!w.writeByteArray(data)) {
                    return false;
                }
                w.incrementState();
        }
        return true;
    }

    @Override
    public boolean readFrom(MessageReader r) {
        switch (r.state()) {
            case 0:
                id = r.readLong();
                if (!r.isLastRead()) {
                    return false;
                }
                r.incrementState();
            case 1:
                payload = r.readString();
                if (!r.isLastRead()) {
                    return false;
                }
                r.incrementState();
            case 2:
                data = r.readByteArray();
                if (!r.isLastRead()) {
                    return false;
                }
                r.incrementState();
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PingMessage that)) {
            return false;
        }
        return id == that.id && Objects.equals(payload, that.payload) && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int h = Long.hashCode(id);
        h = 31 * h + (payload == null ? 0 : payload.hashCode());
        h = 31 * h + Arrays.hashCode(data);
        return h;
    }

    @Override
    public String toString() {
        return "PingMessage{id=" + id + ", payload=" + payload
                + ", data.len=" + (data == null ? -1 : data.length) + "}";
    }
}
