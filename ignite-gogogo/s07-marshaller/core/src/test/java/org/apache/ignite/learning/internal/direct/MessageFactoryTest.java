package org.apache.ignite.learning.internal.direct;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MessageFactory} 的注册/创建/白名单测试(镜像 {@code IgniteMessageFactoryImpl} 的 +32768 数组下标)。
 */
class MessageFactoryTest {

    @Test
    void createByDirectType() {
        MessageFactory f = new MessageFactory();
        f.register(PingMessage.TYPE, PingMessage::new);
        // 负 type 也合法(+32768 下标映射),镜像 Ignite 的 NODE_ID=-1 / HANDSHAKE=-3
        f.register((short) -3, PingMessage::new);
        f.initialized();

        // 正 type 创建
        assertInstanceOf(PingMessage.class, f.create(PingMessage.TYPE));
        // 负 type 经 +32768 shift 也能创建(验证数组下标不越界、查到正确工厂)
        assertInstanceOf(PingMessage.class, f.create((short) -3));

        // 未知 type → 白名单拒绝(只有注册过的 type 能构造)
        assertThrows(IllegalStateException.class, () -> f.create((short) 9999));

        // 建成后再 register → 拒绝(运行期只读)
        assertThrows(IllegalStateException.class, () -> f.register((short) 50, PingMessage::new));
    }
}
