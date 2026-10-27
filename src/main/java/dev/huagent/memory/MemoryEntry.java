package dev.huagent.memory;

import java.nio.file.Path;

/** 索引中的一条记忆：只有元数据，正文在 memory_read 调用时才从 file 读取。 */
public record MemoryEntry(String name, MemoryType type, String description, Path file) {
}
