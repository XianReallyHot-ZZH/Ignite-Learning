package org.apache.ignite.learning.internal.direct;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 测试夹具:覆盖 v1 全部字段类型(原语 + 数组 + String + UUID + 嵌套 Message)。
 * 字段读写顺序固定(case 0..10),用于 {@link DirectMessageRoundtripTest} 的往返断言。
 */
class SampleMessage implements Message {

    static final short TYPE = 10;

    byte b;
    short s;
    int i;
    long l;
    boolean z;
    byte[] ba;
    int[] ia;
    long[] la;
    String str;
    UUID uid;
    Message inner;

    @Override
    public short directType() {
        return TYPE;
    }

    @Override
    public boolean writeTo(MessageWriter w) {
        switch (w.state()) {
            case 0:
                if (!w.writeByte(b)) return false;
                w.incrementState();
            case 1:
                if (!w.writeShort(s)) return false;
                w.incrementState();
            case 2:
                if (!w.writeInt(i)) return false;
                w.incrementState();
            case 3:
                if (!w.writeLong(l)) return false;
                w.incrementState();
            case 4:
                if (!w.writeBoolean(z)) return false;
                w.incrementState();
            case 5:
                if (!w.writeByteArray(ba)) return false;
                w.incrementState();
            case 6:
                if (!w.writeIntArray(ia)) return false;
                w.incrementState();
            case 7:
                if (!w.writeLongArray(la)) return false;
                w.incrementState();
            case 8:
                if (!w.writeString(str)) return false;
                w.incrementState();
            case 9:
                if (!w.writeUuid(uid)) return false;
                w.incrementState();
            case 10:
                if (!w.writeMessage(inner)) return false;
                w.incrementState();
        }
        return true;
    }

    @Override
    public boolean readFrom(MessageReader r) {
        switch (r.state()) {
            case 0:
                b = r.readByte();
                if (!r.isLastRead()) return false;
                r.incrementState();
            case 1:
                s = r.readShort();
                if (!r.isLastRead()) return false;
                r.incrementState();
            case 2:
                i = r.readInt();
                if (!r.isLastRead()) return false;
                r.incrementState();
            case 3:
                l = r.readLong();
                if (!r.isLastRead()) return false;
                r.incrementState();
            case 4:
                z = r.readBoolean();
                if (!r.isLastRead()) return false;
                r.incrementState();
            case 5:
                ba = r.readByteArray();
                if (!r.isLastRead()) return false;
                r.incrementState();
            case 6:
                ia = r.readIntArray();
                if (!r.isLastRead()) return false;
                r.incrementState();
            case 7:
                la = r.readLongArray();
                if (!r.isLastRead()) return false;
                r.incrementState();
            case 8:
                str = r.readString();
                if (!r.isLastRead()) return false;
                r.incrementState();
            case 9:
                uid = r.readUuid();
                if (!r.isLastRead()) return false;
                r.incrementState();
            case 10:
                inner = r.readMessage();
                if (!r.isLastRead()) return false;
                r.incrementState();
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SampleMessage that)) return false;
        return b == that.b && s == that.s && i == that.i && l == that.l && z == that.z
                && Arrays.equals(ba, that.ba) && Arrays.equals(ia, that.ia) && Arrays.equals(la, that.la)
                && Objects.equals(str, that.str) && Objects.equals(uid, that.uid)
                && Objects.equals(inner, that.inner);
    }

    @Override
    public int hashCode() {
        int h = Byte.hashCode(b);
        h = 31 * h + Short.hashCode(s);
        h = 31 * h + i;
        h = 31 * h + Long.hashCode(l);
        h = 31 * h + Boolean.hashCode(z);
        h = 31 * h + Arrays.hashCode(ba);
        h = 31 * h + Arrays.hashCode(ia);
        h = 31 * h + Arrays.hashCode(la);
        h = 31 * h + (str == null ? 0 : str.hashCode());
        h = 31 * h + (uid == null ? 0 : uid.hashCode());
        h = 31 * h + (inner == null ? 0 : inner.hashCode());
        return h;
    }
}
