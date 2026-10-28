package dev.huagent.subagent;

import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.tool.ToolRegistry;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubagentRunnerTest {
    private final StringWriter buffer = new StringWriter();
    private final List<String> executed = new ArrayList<>();

    @Test
    void startsFromFreshHistoryAndReturnsOnlyFinalText() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        SubagentRunner runner = runner((messages, definitions) -> {
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant("先查一下", List.of(new ToolCall("c1", "echo", "{}")))
                    : new ChatMessage("assistant", "  找到 3 个文件  ");
        }, 5);
        SubagentRunner.Result result = runner.run("查找", "找出所有 txt");
        assertEquals("找到 3 个文件", result.text());
        assertEquals(2, result.modelCalls());
        assertEquals(1, result.toolCalls());
        assertTrue(result.completed());
        assertEquals(List.of("system", "user"), requests.get(0).stream().map(ChatMessage::role).toList());
        assertEquals("找出所有 txt", requests.get(0).get(1).content());
        assertEquals(List.of("system", "user", "assistant", "tool"),
                requests.get(1).stream().map(ChatMessage::role).toList());
        assertEquals("echo-result", requests.get(1).get(3).content());
        assertEquals(List.of("echo"), executed);
        assertTrue(buffer.toString().contains("子代理[查找] 工具请求> echo [c1]"), buffer.toString());
    }

    @Test
    void eachRunIsIndependent() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        SubagentRunner runner = runner((messages, definitions) -> {
            requests.add(messages);
            return new ChatMessage("assistant", "完成 " + requests.size());
        }, 5);
        runner.run("一", "任务一");
        runner.run("二", "任务二");
        assertEquals(2, requests.get(1).size());
        assertEquals("任务二", requests.get(1).get(1).content());
    }

    @Test
    void stopsAtLimitWithoutExecutingLastBatch() throws Exception {
        int[] calls = {0};
        SubagentRunner runner = runner((messages, definitions) -> {
            calls[0]++;
            return ChatMessage.assistant("第 " + calls[0] + " 步", List.of(new ToolCall("c" + calls[0], "echo", "{}")));
        }, 2);
        SubagentRunner.Result result = runner.run("循环", "一直调用");
        assertFalse(result.completed());
        assertEquals(2, calls[0]);
        assertEquals(1, executed.size());
        assertTrue(result.text().startsWith("子代理未完成：已达到模型调用上限（2 次）"), result.text());
        assertTrue(result.text().endsWith("最后的说明：第 2 步"), result.text());
    }

    @Test
    void blankFinalAnswerIsReported() throws Exception {
        SubagentRunner runner = runner((messages, definitions) -> new ChatMessage("assistant", " "), 3);
        assertEquals("子代理结束，但没有返回文字。", runner.run("空", "任务").text());
    }

    @Test
    void modelFailurePropagates() {
        SubagentRunner runner = runner((messages, definitions) -> {
            throw new HttpTimeoutException("timeout");
        }, 3);
        assertThrows(HttpTimeoutException.class, () -> runner.run("超时", "任务"));
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> runner((m, d) -> null, 0));
    }

    @Test
    void nonDebugPrintsNothing() throws Exception {
        int[] calls = {0};
        SubagentRunner runner = runner((messages, definitions) -> ++calls[0] == 1
                ? ChatMessage.assistant(null, List.of(new ToolCall("c1", "echo", "{}")))
                : new ChatMessage("assistant", "完成"), 5, false);
        assertEquals("完成", runner.run("查找", "任务").text());
        assertEquals(List.of("echo"), executed);
        assertEquals("", buffer.toString());
    }

    private SubagentRunner runner(ModelClient client, int maxCalls) {
        return runner(client, maxCalls, true);
    }

    private SubagentRunner runner(ModelClient client, int maxCalls, boolean debug) {
        AgentTool echo = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("echo", "测试工具", new JSONObject());
            }

            @Override
            public String execute(String arguments) throws IOException {
                executed.add("echo");
                return "echo-result";
            }
        };
        return new SubagentRunner(client, new ToolRegistry(List.of(echo)), maxCalls, "子代理提示词",
                new PrintWriter(buffer), debug);
    }
}
