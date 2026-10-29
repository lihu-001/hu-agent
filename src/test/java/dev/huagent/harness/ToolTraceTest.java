package dev.huagent.harness;

import dev.huagent.tool.ToolCall;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ToolTraceTest {
    private static final ToolCall CALL = new ToolCall("call_1", "shell", "{\"command\":\"mvn test\"}");

    @Test
    void keepsIdArgumentsAndFullResult() {
        String result = IntStream.rangeClosed(1, 20).mapToObj(i -> "第 " + i + " 行").collect(Collectors.joining("\n"));
        assertEquals("工具请求> shell [call_1] {\"command\":\"mvn test\"}", ToolTrace.request(CALL));
        assertEquals("工具结果> [call_1] " + result, ToolTrace.result(CALL, result));
    }

    @Test
    void summaryShowsReadOnlyArgumentsAndAShortOutcome() {
        assertEquals("工具> shell · 退出码 0，2 行输出", ToolTrace.summary(CALL, "退出码：0\nBUILD SUCCESS\n总计 3 秒\n"));
        assertEquals("工具> shell · 退出码 1，无输出", ToolTrace.summary(CALL, "退出码：1\n"));
        assertEquals("工具> read_file src/A.java 第 40 行起 · 2 行（未读完）", ToolTrace.summary(
                new ToolCall("r", "read_file", "{\"path\":\"src/A.java\",\"offset\":40}"),
                "    40\ta\n    41\tb\n[已显示第 40–41 行，共 90 行；用 offset=42 继续读取]"));
        assertEquals("工具> grep 上下文剩余（src，*.java） · 找到 1 个文件", ToolTrace.summary(
                new ToolCall("g", "grep", "{\"pattern\":\"上下文剩余\",\"path\":\"src\",\"glob\":\"*.java\"}"),
                "找到 1 个文件\nsrc/Prompt.java"));
        assertEquals("工具> grep 不存在 · 无匹配", ToolTrace.summary(
                new ToolCall("g", "grep", "{\"pattern\":\"不存在\"}"), "[无匹配]（搜索了 3 个文件）"));
        assertEquals("工具> glob **/*.java · 2 项", ToolTrace.summary(
                new ToolCall("f", "glob", "{\"pattern\":\"**/*.java\"}"), "a.java\nb/c.java"));
        assertEquals("工具> edit_file · 权限拒绝：用户未批准本次调用。 工具未执行。", ToolTrace.summary(
                new ToolCall("e", "edit_file", "{\"path\":\"a\",\"old_text\":\"x\",\"new_text\":\"y\"}"),
                "权限拒绝：用户未批准本次调用。 工具未执行。"));
    }

    @Test
    void summaryEscapesControlCharactersAndClipsLongText() {
        String summary = ToolTrace.summary(new ToolCall("g", "grep", "{\"pattern\":\"" + "长".repeat(100) + "\"}"),
                "找到 1 个文件\u001b[2J");
        assertTrue(summary.startsWith("工具> grep " + "长".repeat(39) + "… · "), summary);
        assertTrue(summary.endsWith("找到 1 个文件\\u001b[2J"), summary);
    }
}
