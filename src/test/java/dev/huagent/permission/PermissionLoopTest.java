package dev.huagent.permission;

import dev.huagent.app.PermissionMain;
import dev.huagent.app.ToolUseMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.loop.ChatLoop;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.tool.file.WorkspaceFiles;

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

class PermissionLoopTest {
    @TempDir
    Path root;

    @Test
    void mixedBatchReturnsEveryCallIdAndKeepsConfirmationOutOfHistory() throws Exception {
        Files.writeString(root.resolve("protected.txt"), "original");
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("执行\nn\ny\n下一步\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob"),
                    definitions.stream().map(ToolDefinition::name).toList());
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    call("denied", "write_file", Map.of("path", "protected.txt", "content", "changed")),
                    call("approved", "write_file", Map.of("path", "created.txt", "content", "已批准")),
                    call("read", "read_file", Map.of("path", "created.txt")),
                    call("find", "glob", Map.of("pattern", "*.txt")),
                    // 即使规则回归也只是 echo，不会实际执行危险命令。
                    call("blocked", "shell", Map.of("command", "echo sudo")),
                    call("unknown", "missing", Map.of())))
                    : new ChatMessage("assistant", "完成");
        }, 10);
        assertEquals(3, requests.size());
        List<ChatMessage> results = requests.get(1).subList(3, 9);
        assertEquals(List.of("denied", "approved", "read", "find", "blocked", "unknown"),
                results.stream().map(ChatMessage::toolCallId).toList());
        assertTrue(results.get(0).content().startsWith("权限拒绝"));
        assertTrue(results.get(1).content().startsWith("已写入"));
        assertEquals("已批准", results.get(2).content());
        assertEquals("created.txt\nprotected.txt", results.get(3).content());
        assertTrue(results.get(4).content().startsWith("权限拒绝"));
        assertTrue(results.get(5).content().contains("未知工具"));
        assertEquals("original", Files.readString(root.resolve("protected.txt")));
        assertEquals("已批准", Files.readString(root.resolve("created.txt")));
        assertEquals(List.of("执行", "下一步"), requests.get(2).stream()
                .filter(message -> "user".equals(message.role())).map(ChatMessage::content).toList());
        assertEquals(2, output.split("权限确认>", -1).length - 1);
    }

    @Test
    void approvalDoesNotCarryFromWriteToEdit() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        run("执行\ny\nn\n", (messages, definitions) -> {
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    call("write", "write_file", Map.of("path", "note.txt", "content", "first")),
                    call("edit", "edit_file", Map.of("path", "note.txt", "old_text", "first", "new_text", "second"))))
                    : new ChatMessage("assistant", "编辑未批准");
        }, 10);
        assertEquals("first", Files.readString(root.resolve("note.txt")));
        assertTrue(requests.get(1).get(4).content().startsWith("权限拒绝"));
    }

    @Test
    void approvedEditAndShellUseExistingTools() throws Exception {
        Files.writeString(root.resolve("note.txt"), "first");
        List<List<ChatMessage>> requests = new ArrayList<>();
        run("执行\ny\ny\n", (messages, definitions) -> {
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    call("edit", "edit_file", Map.of("path", "note.txt", "old_text", "first", "new_text", "second")),
                    call("shell", "shell", Map.of("command", "echo permission-ok"))))
                    : new ChatMessage("assistant", "完成");
        }, 10);
        assertEquals("second", Files.readString(root.resolve("note.txt")));
        assertTrue(requests.get(1).get(4).content().contains("permission-ok"));
        assertTrue(requests.get(1).get(4).content().contains("退出码：0"));
    }

    @Test
    void eofDuringBatchDeniesRemainingWritesButCompletesResults() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        run("执行\ny\n", (messages, definitions) -> {
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    call("first", "write_file", Map.of("path", "first.txt", "content", "x")),
                    call("second", "write_file", Map.of("path", "second.txt", "content", "x")),
                    call("third", "write_file", Map.of("path", "third.txt", "content", "x"))))
                    : new ChatMessage("assistant", "部分完成");
        }, 10);
        assertTrue(Files.exists(root.resolve("first.txt")));
        assertFalse(Files.exists(root.resolve("second.txt")));
        assertFalse(Files.exists(root.resolve("third.txt")));
        assertEquals(List.of("first", "second", "third"), requests.get(1).subList(3, 6).stream()
                .map(ChatMessage::toolCallId).toList());
        assertTrue(requests.get(1).get(4).content().contains("输入已结束"));
    }

    @Test
    void callLimitDoesNotAskOrExecute() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("执行\n下一步\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    call("write", "write_file", Map.of("path", "note.txt", "content", "x"))))
                    : new ChatMessage("assistant", "继续");
        }, 1);
        assertFalse(Files.exists(root.resolve("note.txt")));
        assertFalse(output.contains("权限确认>"));
        assertTrue(requests.get(1).get(3).content().contains("工具未执行"));
        assertEquals("下一步", requests.get(1).get(5).content());
    }

    private String run(String text, ModelClient client, int maxCalls) throws IOException {
        BufferedReader input = new BufferedReader(new StringReader(text));
        StringWriter textOutput = new StringWriter();
        PrintWriter output = new PrintWriter(textOutput);
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake", root,
                maxCalls, Duration.ofSeconds(5), 12000);
        PermissionGate gate = new PermissionGate(new PermissionPolicy(new WorkspaceFiles(root)), input, output);
        new ChatLoop(client, ToolUseMain.createTools(config, gate), maxCalls, PermissionMain.SYSTEM_PROMPT)
                .run(input, output);
        return textOutput.toString();
    }

    private ToolCall call(String id, String name, Map<String, Object> args) {
        return new ToolCall(id, name, JSON.toJSONString(args));
    }
}
