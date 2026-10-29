package dev.huagent.hook;

import dev.huagent.app.HooksMain;
import dev.huagent.app.ToolUseMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.loop.ChatLoop;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolRegistry;

import com.alibaba.fastjson2.JSON;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HooksLoopTest {
    @TempDir
    Path root;

    @Test
    void mixedBatchPreservesIdsAndOnlyExecutedToolsRunPostHooks() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("执行\nn\ny\n下一步\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            assertEquals(6, definitions.size());
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    call("blocked", "write_file", Map.of("path", "blocked.txt", "content", "x")),
                    call("denied", "write_file", Map.of("path", "denied.txt", "content", "x")),
                    call("allowed", "write_file", Map.of("path", "allowed.txt", "content", "中文")),
                    call("read", "read_file", Map.of("path", "allowed.txt")),
                    // 仅为安全的 echo 字符串；硬拒绝规则仍必须生效。
                    call("hard-deny", "shell", Map.of("command", "echo sudo")),
                    call("bad-json", "write_file", Map.of("path", 42)),
                    new ToolCall("malformed", "write_file", "{"),
                    call("unknown", "missing", Map.of())))
                    : new ChatMessage("assistant", "完成");
        }, 5, new AgentHook() {
            public String preToolUse(ToolCall call) {
                return "blocked".equals(call.id()) ? "自定义前置检查拒绝" : null;
            }
        });

        List<ChatMessage> results = requests.get(1).stream().filter(m -> "tool".equals(m.role())).toList();
        assertEquals(List.of("blocked", "denied", "allowed", "read",
                "hard-deny", "bad-json", "malformed", "unknown"),
                results.stream().map(ChatMessage::toolCallId).toList());
        assertTrue(results.get(0).content().startsWith("钩子拒绝"));
        assertTrue(results.get(1).content().startsWith("权限拒绝"));
        assertEquals("     1\t中文", results.get(3).content());
        assertTrue(results.get(4).content().startsWith("权限拒绝"));
        assertTrue(results.get(5).content().startsWith("权限拒绝"));
        assertTrue(results.get(6).content().startsWith("权限拒绝"));
        assertTrue(results.get(7).content().contains("未知工具"));
        assertEquals("中文", Files.readString(root.resolve("allowed.txt")));
        assertFalse(Files.exists(root.resolve("blocked.txt")));
        assertFalse(Files.exists(root.resolve("denied.txt")));
        assertEquals(2, count(output, "权限确认>"));
        assertEquals(7, count(output, "钩子 PreToolUse>"));
        assertEquals(2, count(output, "钩子 PostToolUse>"));
        assertEquals(2, count(output, "钩子 UserPromptSubmit>"));
        assertEquals(2, count(output, "钩子 Stop>"));
        assertEquals(List.of("执行", "下一步"), requests.get(2).stream()
                .filter(m -> "user".equals(m.role())).map(ChatMessage::content).toList());
        assertTrue(requests.get(2).stream().noneMatch(m -> m.content() != null
                && m.content().contains("钩子 PostToolUse>")));
    }

    @Test
    void modelCallLimitDoesNotTriggerHooksPermissionOrWrites() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("执行\n下一步\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    call("write", "write_file", Map.of("path", "note.txt", "content", "x"))))
                    : new ChatMessage("assistant", "继续");
        }, 1);
        assertFalse(Files.exists(root.resolve("note.txt")));
        assertFalse(output.contains("钩子 PreToolUse>"));
        assertFalse(output.contains("钩子 PostToolUse>"));
        assertFalse(output.contains("权限确认>"));
        assertEquals(2, count(output, "钩子 UserPromptSubmit>"));
        assertEquals(1, count(output, "钩子 Stop>")); // 只有下一轮正常回复触发。
        assertTrue(requests.get(1).stream().anyMatch(m -> "write".equals(m.toolCallId())
                && m.content().contains("调用上限")));
    }

    @Test
    void postFailureAfterRealWriteStillReturnsOriginalResultToModel() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        ToolRegistry registry = ToolUseMain.createTools(config(3), request -> null,
                List.of(new AgentHook() {
                    public void postToolUse(ToolCall request, String result, long elapsedNanos) throws IOException {
                        throw new IOException("private");
                    }
                }), warnings::add);
        new ChatLoop((messages, definitions) -> {
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    call("write", "write_file", Map.of("path", "note.txt", "content", "已写入"))))
                    : new ChatMessage("assistant", "完成");
        }, registry, 3, HooksMain.SYSTEM_PROMPT).run(new BufferedReader(new StringReader("执行\nexit\n")),
                new PrintWriter(new StringWriter()));
        assertEquals("已写入", Files.readString(root.resolve("note.txt")));
        ChatMessage result = requests.get(1).stream().filter(m -> "tool".equals(m.role())).findFirst().orElseThrow();
        assertEquals("write", result.toolCallId());
        assertTrue(result.content().contains("9"));
        assertFalse(result.content().contains("失败"));
        assertEquals(1, warnings.size());
    }

    @Test
    void eachWriteStillNeedsItsOwnPermission() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        run("执行\ny\nn\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    call("first", "write_file", Map.of("path", "note.txt", "content", "第一版")),
                    call("second", "edit_file", Map.of("path", "note.txt",
                            "old_text", "第一版", "new_text", "第二版"))))
                    : new ChatMessage("assistant", "完成");
        }, 3);
        assertEquals("第一版", Files.readString(root.resolve("note.txt")));
        assertTrue(requests.get(1).stream().anyMatch(m -> "second".equals(m.toolCallId())
                && m.content().startsWith("权限拒绝")));
    }

    private String run(String text, ModelClient client, int maxCalls, AgentHook... custom) throws IOException {
        BufferedReader input = new BufferedReader(new StringReader(text));
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        HooksMain.createLoop(config(maxCalls), client, input, output, root, List.of(custom)).run(input, output);
        return buffer.toString();
    }

    private AgentConfig config(int maxCalls) {
        return new AgentConfig("http://localhost", "unused", "fake", root, maxCalls,
                Duration.ofSeconds(5), 12000);
    }

    private ToolCall call(String id, String name, Map<String, Object> args) {
        return new ToolCall(id, name, JSON.toJSONString(args));
    }

    private int count(String value, String fragment) {
        return value.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }
}
