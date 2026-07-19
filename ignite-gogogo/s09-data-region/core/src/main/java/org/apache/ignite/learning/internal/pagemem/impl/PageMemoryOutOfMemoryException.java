package org.apache.ignite.learning.internal.pagemem.impl;

/**
 * 页内存耗尽异常(镜像 Ignite {@code IgniteOutOfMemoryException} 的页内存场景)。
 * S8 单段无 free-list,段满即抛;S9 加 free-list 后仅当 free-list 空 + 段满才抛。
 */
public class PageMemoryOutOfMemoryException extends RuntimeException {
    public PageMemoryOutOfMemoryException(String message) {
        super(message);
    }
}
