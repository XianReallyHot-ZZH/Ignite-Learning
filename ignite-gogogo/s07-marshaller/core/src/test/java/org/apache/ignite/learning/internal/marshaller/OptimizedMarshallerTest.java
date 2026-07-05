package org.apache.ignite.learning.internal.marshaller;

import java.io.Serializable;
import java.util.Objects;
import org.apache.ignite.learning.internal.marshaller.jdk.JdkMarshaller;
import org.apache.ignite.learning.internal.marshaller.optimized.OptimizedMarshaller;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OptimizedMarshaller} 的往返 / 环 / 体积对比测试。
 */
class OptimizedMarshallerTest {

    private final OptimizedMarshaller marsh = new OptimizedMarshaller();

    /** marshal → unmarshal 一来一回。 */
    private <T> T roundtrip(T src) throws Exception {
        return marsh.unmarshal(marsh.marshal(src), null);
    }

    @Test
    void pojoRoundtrip() throws Exception {
        Person src = new Person("Alice", 30, 0x1234L, true, "alice@x.io", new byte[]{1, 2, 3, (byte) 0xFF});

        Person dst = roundtrip(src);

        assertEquals(src, dst);
        assertArrayEquals(src.data, dst.data);
    }

    @Test
    void nestedAndArrayRoundtrip() throws Exception {
        Outer src = new Outer();
        src.x = 42;
        src.inner = new Inner();
        src.inner.s = "deep";
        src.inner.n = -99L;
        src.tags = new String[]{"a", "b", "ü中"};
        src.misc = new Object[]{"x", 7, null, true};

        Outer dst = roundtrip(src);

        assertEquals(src.x, dst.x);
        assertNotNull(dst.inner);
        assertEquals("deep", dst.inner.s);
        assertEquals(-99L, dst.inner.n);
        assertArrayEquals(src.tags, dst.tags);
        assertEquals("x", dst.misc[0]);
        assertEquals(7, dst.misc[1]);
        assertNullAt(dst.misc, 2);
        assertEquals(true, dst.misc[3]);
    }

    @Test
    void cyclicGraphRoundtrip() throws Exception {
        // 构造环:a → next → b → next → a;且 peer 互指
        Node a = new Node("a");
        Node b = new Node("b");
        a.next = b;
        b.next = a; // 环
        a.peer = b;
        b.peer = a;

        Node a2 = roundtrip(a);

        assertEquals("a", a2.name);
        Node b2 = a2.next;
        assertEquals("b", b2.name);
        // 环 + 共享引用保持(不重复构造,不栈溢出)
        assertSame(a2, b2.next, "cycle a2→b2→a2 must be preserved");
        assertSame(b2, a2.peer, "a2.peer must be b2");
        assertSame(a2, b2.peer, "b2.peer must be a2");
    }

    @Test
    void smallerThanJdkSerialization() throws Exception {
        Person src = new Person("Bob", 25, 99L, false, "bob@x.io", new byte[]{7, 7, 7});

        byte[] optimized = marsh.marshal(src);
        byte[] jdk = new JdkMarshaller().marshal(src);

        assertTrue(optimized.length < jdk.length,
                "Optimized should be smaller than JDK serialization: optimized=" + optimized.length
                        + ", jdk=" + jdk.length);
    }

    // ---- 小工具 ----

    private static void assertNullAt(Object[] arr, int i) {
        assertTrue(arr[i] == null, "expected null at index " + i);
    }

    // ---- 测试夹具 POJO ----

    /** 含嵌套对象 + 多种数组字段。static 嵌套(避免 this$0 合成字段被序列化)。 */
    static class Outer implements Serializable {
        private static final long serialVersionUID = 1L;
        int x;
        Inner inner;
        String[] tags;
        Object[] misc;
    }

    static class Inner implements Serializable {
        private static final long serialVersionUID = 1L;
        String s;
        long n;
    }

    /** 环测试节点:equals 仅按 name(避免环引用引发 equals 无限递归)。name 非 final + 提供无参构造器
     * (反射实例化需要;Ignite 用 {@code Unsafe.allocateInstance} 可省无参构造器,学习版避开 Unsafe 故要求)。 */
    static class Node implements Serializable {
        private static final long serialVersionUID = 1L;
        String name;
        Node next;
        Node peer;

        Node() {
            // 无参构造器:供 OptimizedMarshaller 反射实例化
        }

        Node(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Node that)) return false;
            return Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }
    }
}
