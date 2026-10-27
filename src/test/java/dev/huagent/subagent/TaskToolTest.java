package dev.huagent.subagent;

import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.ToolRegistry;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskToolTest {
    private final List<List<ChatMessage>> requests = new ArrayList<>();

    @Test
    void definitionNamesChildToolsAndRequiresBothFields() {
        TaskTool tool = tool((messages, definitions) -> null);
        assertEquals("task", tool.definition().name());
        assertEquals(List.of("description", "prompt"),
                tool.definition().parameters().getJSONArray("required").toJavaList(String.class));
    }

    @Test
    void returnsSummaryWithCounts() throws Exception {
        TaskTool tool = tool((messages, definitions) -> {
            requests.add(messages);
            return new ChatMessage("assistant", "结论");
        });
        String result = tool.execute(args("调研", "  看看目录  "));
        assertEquals("子代理结果（模型请求 1 次，工具调用 0 个）：\n结论", result);
        assertEquals("看看目录", requests.get(0).get(1).content());
    }

    @Test
    void validatesArguments() {
        TaskTool tool = tool((messages, definitions) -> fail("不应请求模型"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("not json"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(args("", "x")));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(args("a", " ")));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(JSON.toJSONString(
                Map.of("description", "a", "prompt", "b", "extra", 1))));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(args("a".repeat(41), "b")));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(args("a", "b".repeat(8001))));
    }

    @Test
    void modelFailuresBecomeToolErrors() throws Exception {
        assertTrue(tool((m, d) -> {
            throw new HttpTimeoutException("t");
        }).execute(args("a", "b")).startsWith("工具错误：子代理模型请求超时"));
        assertTrue(tool((m, d) -> {
            throw new IOException("x");
        }).execute(args("a", "b")).startsWith("工具错误：子代理模型请求失败（IOException）"));
    }

    @Test
    void longResultIsTruncated() throws Exception {
        String result = tool((m, d) -> new ChatMessage("assistant", "x".repeat(TaskTool.MAX_RESULT_CHARS + 10)))
                .execute(args("a", "b"));
        assertTrue(result.endsWith("…（子代理结果已截断）"));
        assertEquals(TaskTool.MAX_RESULT_CHARS, result.chars().filter(c -> c == 'x').count());
    }

    private static TaskTool tool(ModelClient client) {
        return new TaskTool(new SubagentRunner(client, new ToolRegistry(List.of()), 3, "p",
                new PrintWriter(new StringWriter())));
    }

    private static String args(String description, String prompt) {
        return JSON.toJSONString(Map.of("description", description, "prompt", prompt));
    }
}
