package dev.huagent.memory;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryToolsTest {
    @TempDir
    Path root;
    private MemoryStore store;
    private MemoryWriteTool write;
    private MemoryReadTool read;
    private MemoryDeleteTool delete;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(root.resolve(MemoryStore.DIRECTORY_NAME));
        write = new MemoryWriteTool(store);
        read = new MemoryReadTool(store);
        delete = new MemoryDeleteTool(store);
    }

    @Test
    void definitionsHaveNamesAndRequiredFields() {
        assertEquals("memory_write", write.definition().name());
        assertEquals("memory_read", read.definition().name());
        assertEquals("memory_delete", delete.definition().name());
        assertEquals(List.of("name", "type", "description", "content"),
                write.definition().parameters().getJSONArray("required").toJavaList(String.class));
        assertEquals(List.of("user", "feedback", "project", "reference"), write.definition().parameters()
                .getJSONObject("properties").getJSONObject("type").getJSONArray("enum").toJavaList(String.class));
    }

    @Test
    void writeReadDeleteRoundTrip() throws IOException {
        String created = write.execute(args("prefer-tabs", "feedback", "缩进用四个空格", "原因：项目规范"));
        assertTrue(created.startsWith("已新建记忆 prefer-tabs"), created);
        assertTrue(write.execute(args("prefer-tabs", "feedback", "缩进", "更新")).startsWith("已覆盖记忆"));
        assertTrue(read.execute("{\"name\":\" prefer-tabs \"}").contains("更新"));
        assertTrue(delete.execute("{\"name\":\"prefer-tabs\"}").startsWith("已删除记忆 prefer-tabs"));
        assertEquals(List.of(), store.list(w -> fail(w)));
    }

    @Test
    void validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> read.execute("not json"));
        assertThrows(IllegalArgumentException.class, () -> read.execute("{}"));
        assertThrows(IllegalArgumentException.class, () -> read.execute("{\"name\":1}"));
        assertThrows(IllegalArgumentException.class,
                () -> read.execute(JSON.toJSONString(Map.of("name", "a", "extra", 1))));
        assertThrows(IllegalArgumentException.class, () -> read.execute("{\"name\":\"missing\"}"));
        assertThrows(IllegalArgumentException.class, () -> write.execute(args("a", "secret", "d", "c")));
        assertThrows(IllegalArgumentException.class,
                () -> write.execute(JSON.toJSONString(Map.of("name", "a", "type", "user", "description", "d"))));
        assertThrows(IllegalArgumentException.class, () -> write.execute(args("../a", "user", "d", "c")));
        assertThrows(IllegalArgumentException.class, () -> delete.execute("{\"name\":\"missing\"}"));
    }

    private static String args(String name, String type, String description, String content) {
        return JSON.toJSONString(Map.of("name", name, "type", type, "description", description, "content", content));
    }
}
