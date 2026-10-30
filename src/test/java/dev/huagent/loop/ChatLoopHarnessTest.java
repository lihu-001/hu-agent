package dev.huagent.loop;

import dev.huagent.compact.ContextCompactor;
import dev.huagent.compact.TranscriptStore;
import dev.huagent.harness.EventHub;
import dev.huagent.harness.ModelRetryPolicy;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.AgentHooks;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ContextTooLargeException;
import dev.huagent.model.ModelClient;
import dev.huagent.model.ModelUnavailableException;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.tool.ToolRegistry;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** S15：程序事件哨兵轮、限流退避与上下文超长的响应式压缩。 */
class ChatLoopHarnessTest {
    /** 按脚本依次给出响应或异常；记录每次请求的完整消息。 */
    private static final class FakeModel implements ModelClient {
        private final Deque<Object> script = new ArrayDeque<>();
        private final List<List<ChatMessage>> requests = new ArrayList<>();

        @Override
        public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools)
                throws IOException, InterruptedException {
            requests.add(List.copyOf(messages));
            Object next = script.poll();
            if (next instanceof IOException e) {
                throw e;
            }
            return (ChatMessage) next;
        }
    }

    private static final class RecordingHook implements AgentHook {
        final List<String> prompts = new ArrayList<>();
        final List<List<ChatMessage>> stops = new ArrayList<>();

        @Override
        public String userPromptSubmit(String prompt, List<ChatMessage> history) {
            prompts.add(prompt);
            return null;
        }

        @Override
        public void stop(List<ChatMessage> turn) {
            stops.add(List.copyOf(turn));
        }
    }

    private static StringWriter run(ChatLoop loop, String input) throws IOException {
        StringWriter out = new StringWriter();
        loop.run(new BufferedReader(new StringReader(input)), new PrintWriter(out, true));
        return out;
    }

    private static ChatLoop loop(FakeModel model, AgentHook hook, EventHub events, ModelRetryPolicy retry,
                                 ContextCompactor compactor, AgentTool tool) {
        return new ChatLoop(model, new ToolRegistry(tool == null ? List.of() : List.of(tool)), 5, "sys",
                new AgentHooks(hook == null ? List.of() : List.of(hook), message -> { }),
                null, compactor, null, events, retry);
    }

    @Test
    void sentinelTurnDeliversScheduledEventsOnceWithoutPromptHook() throws IOException {
        EventHub hub = new EventHub();
        hub.publish("[s1（备份）] completed\n输出 x");
        FakeModel model = new FakeModel();
        model.script.add(ChatMessage.assistant("好的，已查看事件。", List.of()));
        RecordingHook hook = new RecordingHook();

        StringWriter out = run(loop(model, hook, hub, null, null, null), "<system-events>\nexit\n");

        assertEquals(1, model.requests.size());
        List<ChatMessage> request = model.requests.get(0);
        assertEquals(2, request.size());
        assertEquals("system", request.get(0).role());
        assertEquals("user", request.get(1).role());
        assertTrue(request.get(1).content().startsWith("<system-events>"));
        assertTrue(request.get(1).content().contains("[s1（备份）] completed"));
        // 哨兵行不是用户输入：不触发 UserPromptSubmit，但正常完成并触发 Stop。
        assertTrue(hook.prompts.isEmpty());
        assertEquals(1, hook.stops.size());
        assertTrue(out.toString().contains("事件> 已将程序事件交给模型"));
        assertFalse(hub.hasPending());
    }

    @Test
    void wakeWithoutPendingEventsDoesNotCallModel() throws IOException {
        FakeModel model = new FakeModel();
        StringWriter out = run(loop(model, null, new EventHub(), null, null, null), "<system-events>\nexit\n");
        assertEquals(0, model.requests.size());
        assertFalse(out.toString().contains("模型请求>"));
    }

    @Test
    void eventsDuringUserTurnAppendToUserMessage() throws IOException {
        EventHub hub = new EventHub();
        hub.publish("事件 A");
        FakeModel model = new FakeModel();
        model.script.add(ChatMessage.assistant("回答", List.of()));
        RecordingHook hook = new RecordingHook();

        run(loop(model, hook, hub, null, null, null), "你好\nexit\n");

        List<ChatMessage> request = model.requests.get(0);
        assertEquals("user", request.get(1).role());
        assertTrue(request.get(1).content().startsWith("你好"));
        assertTrue(request.get(1).content().contains("<system-events>"));
        assertTrue(request.get(1).content().contains("事件 A"));
        assertEquals(List.of("你好"), hook.prompts);
    }

    @Test
    void failedFirstRequestRequeuesEventsForNextDelivery() throws IOException {
        EventHub hub = new EventHub();
        hub.publish("事件 B");
        FakeModel model = new FakeModel();
        model.script.add(new IOException("网络断开"));

        StringWriter out = run(loop(model, null, hub, null, null, null), "你好\nexit\n");

        assertTrue(out.toString().contains("请求失败"));
        assertTrue(hub.hasPending());
        assertEquals(List.of("事件 B"), hub.drain());
    }

    @Test
    void rateLimitRetriesWithRetryAfterAdviceThenSucceeds() throws IOException {
        List<Long> slept = new ArrayList<>();
        ModelRetryPolicy retry = new ModelRetryPolicy(2, 1000, slept::add);
        FakeModel model = new FakeModel();
        model.script.add(new ModelUnavailableException("模型接口返回 HTTP 429（限流）", 7L));
        model.script.add(ChatMessage.assistant("完成", List.of()));
        RecordingHook hook = new RecordingHook();

        StringWriter out = run(loop(model, hook, null, retry, null, null), "开始\nexit\n");

        assertEquals(2, model.requests.size());
        assertEquals(List.of(7000L), slept);
        assertTrue(out.toString().contains("第 1 次重试"));
        assertFalse(out.toString().contains("请求失败"));
        assertEquals(1, hook.stops.size());
    }

    @Test
    void rateLimitGivesUpAfterConfiguredRetries() throws IOException {
        ModelRetryPolicy retry = new ModelRetryPolicy(1, 10, millis -> { });
        FakeModel model = new FakeModel();
        model.script.add(new ModelUnavailableException("模型接口返回 HTTP 429（限流）", null));
        model.script.add(new ModelUnavailableException("模型接口返回 HTTP 429（限流）", null));

        StringWriter out = run(loop(model, null, null, retry, null, null), "开始\nexit\n");

        assertEquals(2, model.requests.size());
        assertTrue(out.toString().contains("请求失败：模型接口返回 HTTP 429（限流）；已重试 1 次仍未成功，任务已停止。"
                + "本轮未保存，请重新输入。"), out.toString());
    }

    @Test
    void connectionFailureAndTimeoutRetryThenSucceed() throws IOException {
        List<Long> slept = new ArrayList<>();
        ModelRetryPolicy retry = new ModelRetryPolicy(5, 1000, slept::add);
        FakeModel model = new FakeModel();
        model.script.add(new ModelUnavailableException("连接模型接口失败（ConnectException）", null));
        model.script.add(new HttpTimeoutException("request timed out"));
        model.script.add(ChatMessage.assistant("完成", List.of()));

        StringWriter out = run(loop(model, null, null, retry, null, null), "开始\nexit\n");

        assertEquals(3, model.requests.size());
        assertEquals(List.of(1000L, 2000L), slept);
        assertTrue(out.toString().contains("模型请求> 连接模型接口失败（ConnectException），等待 1 秒后第 1 次重试（最多 5 次）..."),
                out.toString());
        assertTrue(out.toString().contains("模型请求> 模型接口请求超时，等待 2 秒后第 2 次重试（最多 5 次）..."),
                out.toString());
        assertFalse(out.toString().contains("请求失败"));
    }

    @Test
    void stopsTurnAfterDefaultRetriesKeepingRecordedTools() throws IOException {
        ModelRetryPolicy retry = new ModelRetryPolicy(ModelRetryPolicy.DEFAULT_MAX_RETRIES, 0, millis -> { });
        FakeModel model = new FakeModel();
        AtomicInteger executions = new AtomicInteger();
        model.script.add(ChatMessage.assistant(null, List.of(new ToolCall("c1", "echo", "{}"))));
        for (int i = 0; i <= ModelRetryPolicy.DEFAULT_MAX_RETRIES; i++) {
            model.script.add(new ModelUnavailableException("连接模型接口失败（ConnectException）", null));
        }
        model.script.add(ChatMessage.assistant("下一轮完成", List.of()));

        StringWriter out = run(loop(model, null, null, retry, null, echoTool(executions)), "做任务\n下一轮\nexit\n");

        assertEquals(5, ModelRetryPolicy.DEFAULT_MAX_RETRIES);
        // 第一次请求 + 首次失败 + 5 次重试，然后停止本轮；下一轮照常请求。
        assertEquals(8, model.requests.size());
        assertEquals(1, executions.get());
        assertTrue(out.toString().contains("第 5 次重试（最多 5 次）"), out.toString());
        assertFalse(out.toString().contains("第 6 次重试"));
        assertTrue(out.toString().contains("请求失败：连接模型接口失败（ConnectException）；已重试 5 次仍未成功，任务已停止。"
                + "已保留工具调用和结果"), out.toString());
        assertEquals(List.of("system", "user", "assistant", "tool", "user"),
                model.requests.get(7).stream().map(ChatMessage::role).toList());
    }

    @Test
    void nonRetryableFailureStopsWithoutRetry() throws IOException {
        List<Long> slept = new ArrayList<>();
        ModelRetryPolicy retry = new ModelRetryPolicy(5, 1000, slept::add);
        FakeModel model = new FakeModel();
        model.script.add(new IOException("模型接口返回 HTTP 401（api-key 无效或已过期）"));

        StringWriter out = run(loop(model, null, null, retry, null, null), "开始\nexit\n");

        assertEquals(1, model.requests.size());
        assertEquals(List.of(), slept);
        assertTrue(out.toString().contains("请求失败：模型接口返回 HTTP 401（api-key 无效或已过期）；本轮未保存"),
                out.toString());
        assertFalse(out.toString().contains("重试"));
    }

    @Test
    void contextTooLongTriggersReactiveCompactThenRetries(@TempDir Path dir) throws IOException {
        TranscriptStore transcripts = new TranscriptStore(dir);
        ContextCompactor compactor = new ContextCompactor(summaryModel(), transcripts, dir, 100_000, 3, null);
        FakeModel model = new FakeModel();
        AtomicInteger executions = new AtomicInteger();
        model.script.add(ChatMessage.assistant(null, List.of(new ToolCall("c1", "echo", "{}"))));
        model.script.add(new ContextTooLargeException("模型接口返回 HTTP 400（上下文超长）"));
        model.script.add(ChatMessage.assistant("压缩后完成", List.of()));
        model.script.add(ChatMessage.assistant("下一轮完成", List.of()));

        StringWriter out = run(loop(model, null, null, null, compactor, echoTool(executions)),
                "做任务\n下一轮\nexit\n");

        assertEquals(4, model.requests.size());
        assertEquals(1, executions.get());
        List<ChatMessage> third = model.requests.get(2);
        assertEquals(2, third.size());
        assertEquals("system", third.get(0).role());
        assertTrue(third.get(1).content().startsWith("[对话已压缩]"));
        assertTrue(third.get(1).content().contains("摘要：用户在测试。"));
        assertTrue(out.toString().contains("上下文超长"));
        assertTrue(out.toString().contains("助手> 压缩后完成"));
        // 压缩后的历史成为新的对话状态：下一轮从摘要接着发，不再带压缩前的原文。
        List<ChatMessage> fourth = model.requests.get(3);
        assertEquals(List.of("system", "user", "assistant", "user"),
                fourth.stream().map(ChatMessage::role).toList());
        assertTrue(fourth.get(1).content().startsWith("[对话已压缩]"));
        assertEquals("压缩后完成", fourth.get(2).content());
        assertEquals("下一轮", fourth.get(3).content());
        // 完整记录已保存，被总结的原文可从记录找回。
        try (var files = java.nio.file.Files.list(dir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("transcript-")));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static ModelClient summaryModel() {
        return (messages, tools) -> ChatMessage.assistant("摘要：用户在测试。", List.of());
    }

    private static AgentTool echoTool(AtomicInteger executions) {
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("echo", "回显测试工具", JSON.parseObject("{\"type\":\"object\"}"));
            }

            @Override
            public String execute(String arguments) {
                executions.incrementAndGet();
                return "退出码：0\nok";
            }
        };
    }
}
