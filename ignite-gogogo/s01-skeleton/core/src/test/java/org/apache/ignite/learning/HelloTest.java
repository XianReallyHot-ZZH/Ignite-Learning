package org.apache.ignite.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 骨架冒烟测试:能编译、能跑、断言通过。 */
class HelloTest {

    @Test
    void greet() {
        assertEquals("hello, ignite-learning", new Hello().greet());
    }
}
