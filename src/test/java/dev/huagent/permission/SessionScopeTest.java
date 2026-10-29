package dev.huagent.permission;

import dev.huagent.tool.ToolCall;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 回答 a 放行的范围：命令类按完整命令，写文件与编辑合为文件修改，其余按工具。 */
class SessionScopeTest {
    @Test
    void commandsAreScopedToTheExactCommandOfTheSameTool() {
        SessionScope test = SessionScope.of(new ToolCall("1", "shell", "{\"command\":\"mvn test\"}"));
        assertEquals("这条命令", test.label());
        assertEquals(test, SessionScope.of(new ToolCall("2", "shell", "{\"command\":\"mvn test\",\"timeout\":60}")),
                "同一条命令，其他参数不同也算同一范围");
        assertNotEquals(test.key(), SessionScope.of(new ToolCall("3", "shell", "{\"command\":\"mvn test -q\"}")).key());
        assertNotEquals(test.key(), SessionScope.of(new ToolCall("4", "shell",
                "{\"command\":\"mvn test && rm -rf src\"}")).key(), "不按前缀放行");

        SessionScope background = SessionScope.of(new ToolCall("5", "background_run", "{\"command\":\"mvn test\"}"));
        assertEquals("这条后台命令", background.label());
        assertNotEquals(test.key(), background.key(), "放行 shell 的命令不等于放行后台运行它");
        assertEquals("用这条命令创建定时计划",
                SessionScope.of(new ToolCall("6", "schedule_create", "{\"command\":\"mvn test\"}")).label());
    }

    @Test
    void writesAndEditsShareOneScope() {
        SessionScope write = SessionScope.of(new ToolCall("1", "write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}"));
        SessionScope edit = SessionScope.of(new ToolCall("2", "edit_file",
                "{\"path\":\"src/B.java\",\"old_text\":\"a\",\"new_text\":\"b\"}"));
        assertEquals(write, edit);
        assertEquals("文件修改", write.label());
    }

    @Test
    void otherToolsAreScopedByName() {
        SessionScope memory = SessionScope.of(new ToolCall("1", "memory_write", "{\"name\":\"a\"}"));
        assertEquals(memory, SessionScope.of(new ToolCall("2", "memory_write", "{\"name\":\"b\"}")));
        assertNotEquals(memory.key(), SessionScope.of(new ToolCall("3", "memory_delete", "{\"name\":\"a\"}")).key());
        assertEquals("这个工具", SessionScope.of(new ToolCall("4", "mcp__docs__search", "{}")).label());
    }
}
