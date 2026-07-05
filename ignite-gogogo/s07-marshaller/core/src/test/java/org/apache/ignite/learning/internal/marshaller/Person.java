package org.apache.ignite.learning.internal.marshaller;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * 测试夹具:典型 cache 值 POJO(实现 {@code Serializable} + 无参构造器 —— 前者满足 requireSerializable,
 * 后者满足 {@code OptimizedMarshallerUtils.newInstance} 反射实例化)。
 *
 * <p>故意带多种字段类型(String/int/long/boolean/byte[]),让往返 + 体积对比测试有代表性。
 */
public class Person implements Serializable {

    private static final long serialVersionUID = 1L;

    String name;
    int age;
    long id;
    boolean active;
    String email;
    byte[] data;

    public Person() {
        // 无参构造器:供 OptimizedMarshaller 反射实例化
    }

    public Person(String name, int age, long id, boolean active, String email, byte[] data) {
        this.name = name;
        this.age = age;
        this.id = id;
        this.active = active;
        this.email = email;
        this.data = data;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Person that)) return false;
        return age == that.age && id == that.id && active == that.active
                && Objects.equals(name, that.name) && Objects.equals(email, that.email)
                && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, age, id, active, email, Arrays.hashCode(data));
    }

    @Override
    public String toString() {
        return "Person{name=" + name + ", age=" + age + ", id=" + id + ", active=" + active
                + ", email=" + email + ", data.len=" + (data == null ? -1 : data.length) + "}";
    }
}
