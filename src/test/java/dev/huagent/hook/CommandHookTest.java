package dev.huagent.hook;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import dev.huagent.app.HooksMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CommandHookTest {
    @TempDir
    Path root;
    private final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

    @Test
    void defaultAssemblyDeliversAllFourEventsWithJsonStdinAndProjectWorkingDirectory() throws Exception {
        Map<String, Object> events = new LinkedHashMap<>();
        for (String event : List.of("UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop")) {
            events.put(event, List.of(group(capture(event + ".json"))));
        }
        config(events);
        Files.createDirectories(root.resolve("tools"));
        Files.writeString(root.resolve("tools/hello.txt"), "你好，Hook");
        AtomicInteger calls = new AtomicInteger();
        String prompt = "读取文件 ' $(not-executed) ` \" ; 中文";
        String output = run(prompt + "\nexit\n", (messages, definitions) -> {
            if (calls.incrementAndGet() == 1) {
                return ChatMessage.assistant(null, List.of(
                        new ToolCall("read-1", "read_file", "{\"path\":\"hello.txt\"}")));
            }
            return new ChatMessage("assistant", "读取完成");
        });
        JSONObject submit = payload("UserPromptSubmit");
        assertEquals(prompt, submit.getString("prompt"));
        assertEquals(root.toAbsolutePath().toString(), submit.getString("cwd"));
        assertEquals(1, submit.getJSONArray("history").size());
        assertEquals("system", submit.getJSONArray("history").getJSONObject(0).getString("role"));
        JSONObject pre = payload("PreToolUse");
        assertEquals("read_file", pre.getString("tool_name"));
        assertEquals("read-1", pre.getString("tool_call_id"));
        assertEquals("hello.txt", pre.getJSONObject("tool_input").getString("path"));
        assertEquals("{\"path\":\"hello.txt\"}", pre.getString("tool_arguments"));
        JSONObject post = payload("PostToolUse");
        assertTrue(post.getString("tool_response").contains("你好，Hook"));
        assertTrue(post.getLongValue("elapsed_ms") >= 0);
        var turn = payload("Stop").getJSONArray("turn");
        assertEquals(4, turn.size());
        assertEquals("read-1", turn.getJSONObject(1).getJSONArray("tool_calls")
                .getJSONObject(0).getString("id"));
        assertEquals("read-1", turn.getJSONObject(2).getString("tool_call_id"));
        assertEquals("读取完成", turn.getJSONObject(3).getString("content"));
        assertTrue(output.contains("钩子 Stop>"), output);
        assertFalse(output.contains("钩子警告"), output);
    }

    @Test
    void agentJavaAndUserJsonHooksBothReceiveAllEventsWithJavaFirst() throws Exception {
        List<String> events = List.of("UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop");
        Map<String, Object> configured = new LinkedHashMap<>();
        events.forEach(event -> configured.put(event, List.of(group(capture(event + ".json")))));
        config(configured);
        Files.createDirectories(root.resolve("tools"));
        Files.writeString(root.resolve("tools/hello.txt"), "原结果");
        List<String> javaEvents = new ArrayList<>();
        AgentHook agentHook = new AgentHook() {
            private void record(String event) {
                assertFalse(Files.exists(root.resolve(event + ".json")), "Java 应先于用户命令执行");
                javaEvents.add(event);
            }

            public String userPromptSubmit(String prompt, List<ChatMessage> history) {
                record("UserPromptSubmit");
                return null;
            }

            public String preToolUse(ToolCall call) {
                record("PreToolUse");
                return null;
            }

            public void postToolUse(ToolCall call, String result, long elapsedNanos) {
                record("PostToolUse");
            }

            public void stop(List<ChatMessage> turn) {
                record("Stop");
            }
        };
        AtomicInteger calls = new AtomicInteger();
        String output = run("读取文件\nexit\n", (messages, definitions) ->
                calls.incrementAndGet() == 1 ? ChatMessage.assistant(null, List.of(
                        new ToolCall("read", "read_file", "{\"path\":\"hello.txt\"}")))
                        : new ChatMessage("assistant", "完成"), agentHook);
        assertEquals(events, javaEvents);
        for (String event : events) {
            assertEquals(event, payload(event).getString("hook_event_name"));
            assertEquals(1, output.lines().filter(line -> line.contains("钩子 " + event + ">")).count(),
                    "内置日志不应因追加 Java Hook 而缺失或重复");
        }
    }

    @Test
    void agentJavaRejectionStopsUserCommandWithoutRemovingDefaultLogging() throws Exception {
        config(Map.of("UserPromptSubmit", List.of(group(capture("unexpected.json")))));
        String output = run("被拒绝\nexit\n", (messages, definitions) -> fail("不能请求模型"),
                new AgentHook() {
                    public String userPromptSubmit(String prompt, List<ChatMessage> history) {
                        return "Agent 内置检查拒绝";
                    }
                });
        assertTrue(output.contains("Agent 内置检查拒绝"));
        assertTrue(output.contains("钩子 UserPromptSubmit>"));
        assertFalse(Files.exists(root.resolve("unexpected.json")));
    }

    @Test
    void userJsonCanRejectAfterAgentJavaAllows() throws Exception {
        config(Map.of("UserPromptSubmit", List.of(group(print("用户策略拒绝") + "; exit 2"))));
        AtomicInteger javaCalls = new AtomicInteger();
        String output = run("被拒绝\nexit\n", (messages, definitions) -> fail("不能请求模型"),
                new AgentHook() {
                    public String userPromptSubmit(String prompt, List<ChatMessage> history) {
                        javaCalls.incrementAndGet();
                        return null;
                    }
                });
        assertEquals(1, javaCalls.get());
        assertTrue(output.contains("用户策略拒绝"));
        assertTrue(output.contains("钩子 UserPromptSubmit>"));
    }

    @Test
    void javaPostFailureStillRunsUserJsonAndPreservesFinalAnswer() throws Exception {
        config(Map.of("Stop", List.of(group(capture("Stop.json")))));
        String output = run("完成任务\nexit\n",
                (messages, definitions) -> new ChatMessage("assistant", "原回复"),
                new AgentHook() {
                    public void stop(List<ChatMessage> turn) throws IOException {
                        throw new IOException("private");
                    }
                });
        assertEquals("原回复", payload("Stop").getJSONArray("turn").getJSONObject(1).getString("content"));
        assertTrue(output.contains("助手> 原回复"));
        assertTrue(output.contains("Stop 后置回调失败"));
        assertFalse(output.contains("private"));
    }

    @Test
    void toolMatcherAndCommandOrderStopAtFirstRejection() throws Exception {
        config(Map.of("PreToolUse", List.of(
                Map.of("matcher", "shell", "hooks", List.of(command(capture("wrong.json")))),
                Map.of("matcher", "write_file|edit_file", "hooks", List.of(
                        command(capture("first.json")),
                        command(print("自定义拒绝") + "; exit 2"),
                        command(capture("late.json")))))));
        AgentHooks hooks = hooks(new ArrayList<>());
        assertNull(hooks.preToolUse(new ToolCall("read", "read_file", "{}")));
        assertFalse(Files.exists(root.resolve("first.json")));
        String reason = hooks.preToolUse(new ToolCall("write", "write_file", "{}"));
        assertTrue(reason.contains("自定义拒绝"), reason);
        assertTrue(reason.contains("退出码 2"), reason);
        assertTrue(Files.exists(root.resolve("first.json")));
        assertFalse(Files.exists(root.resolve("wrong.json")));
        assertFalse(Files.exists(root.resolve("late.json")));
    }

    @Test
    void blockedPromptNeverReachesModelOrHistory() throws Exception {
        config(Map.of("UserPromptSubmit", List.of(group(print("不接受本次输入") + "; exit 2")),
                "Stop", List.of(group(capture("unexpected.json")))));
        String output = run("拒绝输入\nexit\n", (messages, definitions) -> fail("不能请求模型"));
        assertTrue(output.contains("不接受本次输入"), output);
        assertTrue(output.contains("提示词未记录"), output);
        assertFalse(Files.exists(root.resolve("unexpected.json")));
    }

    @Test
    void allowedCommandStillRequiresPermissionAndRejectedToolHasNoPostEvent() throws Exception {
        config(Map.of("PreToolUse", List.of(group("exit 0")),
                "PostToolUse", List.of(group(capture("unexpected.json")))));
        AtomicInteger calls = new AtomicInteger();
        String output = run("写文件\nn\nexit\n", (messages, definitions) ->
                calls.incrementAndGet() == 1 ? ChatMessage.assistant(null, List.of(
                        new ToolCall("write", "write_file", "{\"path\":\"denied.txt\",\"content\":\"text\"}")))
                        : new ChatMessage("assistant", "未执行"));
        assertTrue(output.contains("权限确认>"), output);
        assertTrue(output.contains("权限拒绝"), output);
        assertFalse(Files.exists(root.resolve("tools/denied.txt")));
        assertFalse(Files.exists(root.resolve("unexpected.json")));
    }

    @Test
    void commandRejectionPreventsPermissionPromptAndFileWrite() throws Exception {
        config(Map.of("PreToolUse", List.of(group(print("禁止写入") + "; exit 2"))));
        AtomicInteger calls = new AtomicInteger();
        String output = run("写文件\nexit\n", (messages, definitions) ->
                calls.incrementAndGet() == 1 ? ChatMessage.assistant(null, List.of(
                        new ToolCall("write", "write_file", "{\"path\":\"denied.txt\",\"content\":\"text\"}")))
                        : new ChatMessage("assistant", "未执行"));
        assertTrue(output.contains("禁止写入"), output);
        assertFalse(output.contains("权限确认>"), output);
        assertFalse(Files.exists(root.resolve("tools/denied.txt")));
    }

    @Test
    void postFailuresPreserveResultAndRunLaterCommands() throws Exception {
        config(Map.of("PostToolUse", List.of(Map.of("hooks", List.of(command("exit 7"),
                        command(capture("post.json"))))),
                "Stop", List.of(Map.of("hooks", List.of(command("exit 2"),
                        command(capture("stop.json")))))));
        List<String> messages = new ArrayList<>();
        AgentHooks hooks = hooks(messages);
        hooks.postToolUse(new ToolCall("x", "read_file", "{}"), "原结果", 123_000_000);
        hooks.stop(List.of(new ChatMessage("assistant", "原回复")));
        assertEquals("原结果", JSON.parseObject(Files.readString(root.resolve("post.json")))
                .getString("tool_response"));
        assertEquals("原回复", JSON.parseObject(Files.readString(root.resolve("stop.json")))
                .getJSONArray("turn").getJSONObject(0).getString("content"));
        assertEquals(2, messages.stream().filter(message -> message.contains("后置回调失败")).count());
    }

    @Test
    void unknownCommandFailsClosedAndMalformedToolArgumentsRemainAvailable() throws Exception {
        config(Map.of("PreToolUse", List.of(group("hook_missing_command_12345"))));
        assertNotNull(hooks(new ArrayList<>()).preToolUse(new ToolCall("x", "shell", "{}")));
        config(Map.of("PreToolUse", List.of(group(capture("malformed.json")))));
        assertNull(hooks(new ArrayList<>()).preToolUse(new ToolCall("x", "shell", "not-json")));
        JSONObject input = JSON.parseObject(Files.readString(root.resolve("malformed.json")));
        assertEquals("not-json", input.getString("tool_input"));
        assertEquals("not-json", input.getString("tool_arguments"));
    }

    @Test
    void timeoutBlocksBeforeAndWarnsAfterWithoutDiscardingLaterCallbacks() {
        List<String> warnings = new ArrayList<>();
        String sleep = windows ? "Start-Sleep -Seconds 20" : "sleep 20";
        AgentHook before = new CommandHook("PreToolUse", Pattern.compile(".*"), sleep, root,
                Duration.ofMillis(300), warnings::add);
        AgentHook after = new CommandHook("Stop", Pattern.compile(".*"), sleep, root,
                Duration.ofMillis(300), warnings::add);
        AtomicInteger later = new AtomicInteger();
        AgentHooks hooks = new AgentHooks(List.of(before, after, new AgentHook() {
            public void stop(List<ChatMessage> turn) {
                later.incrementAndGet();
            }
        }), warnings::add);
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            assertNotNull(hooks.preToolUse(new ToolCall("x", "shell", "{}")));
            hooks.stop(List.of());
        });
        assertEquals(1, later.get());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("后置回调失败")));
    }

    @Test
    void runnerDrainsLargeOutputAndAcceptsIgnoredLargeInput() throws Exception {
        String script = windows ? "[Console]::Write(('x' * 200000))"
                : "i=0; while [ \"$i\" -lt 20000 ]; do printf xxxxxxxxxx; i=$((i+1)); done";
        var result = assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> new HookCommandRunner().run(script, root, "a".repeat(200000), Duration.ofSeconds(10)));
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("输出已截断"));
        assertTrue(result.output().length() < 12100);
    }

    @Test
    void runnerCapturesUtf8StderrAndExitCode() throws Exception {
        String script = windows ? "[Console]::Error.WriteLine('错误原因'); exit 2"
                : "printf '错误原因\\n' >&2; exit 2";
        var result = new HookCommandRunner().run(script, root, "{}", Duration.ofSeconds(10));
        assertEquals(2, result.exitCode());
        assertTrue(result.output().contains("错误原因"), result.output());
    }

    @Test
    void interruptionStopsRunnerPromptly() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger interrupted = new AtomicInteger();
        Thread worker = new Thread(() -> {
            try {
                new HookCommandRunner().run(windows ? "Start-Sleep -Seconds 20" : "sleep 20",
                        root, "{}", Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        worker.start();
        Thread.sleep(500);
        worker.interrupt();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertEquals(1, interrupted.get());
    }

    private JSONObject payload(String event) throws IOException {
        JSONObject value = JSON.parseObject(Files.readString(root.resolve(event + ".json")));
        assertEquals(event, value.getString("hook_event_name"));
        return value;
    }

    private AgentHooks hooks(List<String> output) throws IOException {
        return new AgentHooks(HookConfig.load(root, output::add), output::add);
    }

    private void config(Map<String, Object> events) throws IOException {
        Path file = root.resolve(HookConfig.DEFAULT_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.toJSONString(Map.of("hooks", events)));
    }

    private Map<String, Object> group(String script) {
        return Map.of("hooks", List.of(command(script)));
    }

    private Map<String, Object> command(String script) {
        return Map.of("type", "command", "command", script, "timeout", 10);
    }

    private String capture(String file) {
        return windows
                ? "$payload = [Console]::In.ReadToEnd(); [IO.File]::WriteAllText("
                        + "(Join-Path (Get-Location) '" + file + "'), $payload, [Text.UTF8Encoding]::new($false))"
                : "cat > '" + file + "'";
    }

    private String print(String message) {
        return windows ? "[Console]::WriteLine('" + message + "')" : "printf '%s\\n' '" + message + "'";
    }

    private String run(String text, ModelClient client, AgentHook... agentCallbacks) throws IOException {
        BufferedReader input = new BufferedReader(new StringReader("once\n" + text));
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake", root.resolve("tools"),
                5, Duration.ofSeconds(5), 12000);
        HooksMain.createLoop(config, client, input, output, root, List.of(agentCallbacks),
                new HookTrustStore(root.resolve("test-trust"))).run(input, output);
        return buffer.toString();
    }
}
