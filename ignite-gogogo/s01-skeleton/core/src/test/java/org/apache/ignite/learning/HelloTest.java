package org.apache.ignite.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 骨架冒烟测试:验证多模块 Maven 工程能编译、能跑测试、断言通过。
 * 这是 S1 的唯一验收点(具名测试),也是后续所有 session "mvn test 绿"工作流的起点。
 */
class HelloTest {

    /** 断言 Hello.greet() 返回预期字符串——内容不重要,重要的是"工程能编译能测试"。 */
    @Test
    void greet() {
        assertEquals("hello, ignite-learning", new Hello().greet());
    }
}
