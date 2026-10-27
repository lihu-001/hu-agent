package dev.huagent.harness;

import dev.huagent.tool.ToolCall;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ToolTraceTest {
    private static final ToolCall CALL = new ToolCall("call_1", "shell", "{\"command\":\"mvn test\"}");

    @Test
    void debugKeepsIdArgumentsAndFullResult() {
        String result = lines(20);
        assertEquals("工具请求> shell [call_1] {\"command\":\"mvn test\"}", ToolTrace.request(CALL, true));
        assertEquals("工具结果> [call_1] " + result, ToolTrace.result(CALL, result, true));
    }

    @Test
    void nonDebugShowsMainArgumentWithoutId() {
        assertEquals("工具> shell mvn test", ToolTrace.request(CALL, false));
        assertEquals("工具> read_file README.md", ToolTrace.request(
                new ToolCall("c", "read_file", "{\"path\":\"README.md\",\"limit\":65}"), false));
        assertEquals("工具> memory_write hu-agent-jdk", ToolTrace.request(new ToolCall("c", "memory_write",
                "{\"name\":\"hu-agent-jdk\",\"type\":\"project\",\"content\":\"很长的内容\"}"), false));
    }

    @Test
    void nonDebugSkipsNonStringArguments() {
        assertEquals("工具> todo_write", ToolTrace.request(
                new ToolCall("c", "todo_write", "{\"todos\":[{\"content\":\"a\"}]}"), false));
        assertEquals("工具> task_update 3", ToolTrace.request(
                new ToolCall("c", "task_update", "{\"limit\":1,\"id\":\"3\"}"), false));
        assertEquals("工具> echo", ToolTrace.request(new ToolCall("c", "echo", "{}"), false));
    }

    @Test
    void nonDebugShowsFirstLineOfMultilineArgument() {
        assertEquals("工具> shell cd src …",
                ToolTrace.request(new ToolCall("c", "shell", "{\"command\":\"cd src\\nmvn test\"}"), false));
    }

    @Test
    void nonDebugFallsBackToRawArgumentsWhenNotJsonObject() {
        assertEquals("工具> t not json", ToolTrace.request(new ToolCall("c", "t", "not json"), false));
        String request = ToolTrace.request(new ToolCall("c", "write_file", "y".repeat(1000)), false);
        assertEquals("工具> write_file " + "y".repeat(ToolTrace.ARGUMENT_CHARS) + "…", request);
    }

    @Test
    void nonDebugIndentsOneLineResult() {
        assertEquals("  已写入 a.txt", ToolTrace.result(CALL, "已写入 a.txt\n", false));
        assertEquals("  退出码：0 …（共 2 行）", ToolTrace.result(CALL, "退出码：0\r\nok\n", false));
        assertEquals("  第 1 行 …（共 8 行）", ToolTrace.result(CALL, lines(8), false));
        assertEquals("  （无输出）", ToolTrace.result(CALL, "  \n", false));
    }

    @Test
    void nonDebugCutsLongResultLine() {
        String shown = ToolTrace.result(CALL, "x".repeat(2000), false);
        assertEquals("  " + "x".repeat(ToolTrace.RESULT_CHARS) + "…", shown);
    }

    @Test
    void cutDoesNotSplitSurrogatePairs() {
        String emoji = "😀";
        String arguments = "a".repeat(ToolTrace.ARGUMENT_CHARS - 1) + emoji + "tail";
        String request = ToolTrace.request(new ToolCall("c", "t", arguments), false);
        assertEquals("工具> t " + "a".repeat(ToolTrace.ARGUMENT_CHARS - 1) + "…", request);
    }

    private static String lines(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "第 " + i + " 行").collect(Collectors.joining("\n"));
    }
}
