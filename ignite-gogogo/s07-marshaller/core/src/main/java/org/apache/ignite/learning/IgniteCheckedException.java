package org.apache.ignite.learning;

/**
 * Ignite 风格的 checked exception(镜像 {@code org.apache.ignite.IgniteCheckedException})。
 *
 * <p>学习版:把底层 {@link java.io.IOException} / 反射异常等包成统一 checked 异常,
 * 供 {@code Marshaller} 等 SPI 的 {@code marshal}/{@code unmarshal} 抛出(镜像 Ignite 全抛此异常的约定)。
 */
public class IgniteCheckedException extends Exception {

    public IgniteCheckedException(String message) {
        super(message);
    }

    public IgniteCheckedException(String message, Throwable cause) {
        super(message, cause);
    }

    public IgniteCheckedException(Throwable cause) {
        super(cause);
    }
}
