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
}
