package org.apache.ignite.learning.internal.marshaller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MarshallerContextImpl} 的注册 / 查询 / 系统类型判定测试
 * (镜像 Ignite 注册表语义,但仅进程内 —— 无 transport)。
 */
class MarshallerContextTest {

    @Test
    void registerAndResolve() throws Exception {
        MarshallerContext ctx = new MarshallerContextImpl();
        int typeId = Person.class.getName().hashCode();

        // 新注册 → true
        assertTrue(ctx.registerClassName(typeId, Person.class.getName()));
        assertEquals(Person.class.getName(), ctx.getClassName(typeId));
        assertEquals(Person.class, ctx.getClass(typeId, Person.class.getClassLoader()));

        // 同 typeId 同名再注册 → false(已存在)
        assertFalse(ctx.registerClassName(typeId, Person.class.getName()));

        // 系统类型判定(JDK / Ignite 前缀)
        assertTrue(ctx.isSystemType("java.lang.String"));
        assertTrue(ctx.isSystemType("org.apache.ignite.internal.X"));
        assertFalse(ctx.isSystemType("com.example.User"));

        // 未注册 typeId → ClassNotFoundException
        assertThrows(ClassNotFoundException.class,
                () -> ctx.getClass(99999, Person.class.getClassLoader()));
    }
}
