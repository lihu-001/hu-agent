package dev.huagent.todo;

import dev.huagent.app.TodoMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;

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

class TodoLoopTest {
    @TempDir
    Path root;
    private final TodoList todos = new TodoList();

    @Test
    void todoWriteIsSixthToolRunsWithoutConfirmationAndPassesThroughHooks() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        List<String> events = new ArrayList<>();
        String output = run("规划\nexit\n", (messages, definitions) -> {
            assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob", "grep", "todo_write"),
                    definitions.stream().map(ToolDefinition::name).toList());
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(todo("plan",
                    item("读取", "in_progress"), item("修改", "pending"))))
                    : new ChatMessage("assistant", "已规划");
        }, 10, new AgentHook() {
            @Override
            public String preToolUse(ToolCall call) {
                events.add("pre:" + call.name());
                return null;
            }

            @Override
            public void postToolUse(ToolCall call, String result, long elapsedNanos) {
                events.add("post:" + call.name());
            }
        });
        List<ChatMessage> second = requests.get(1);
        ChatMessage result = second.get(second.size() - 1);
        assertEquals("plan", result.toolCallId());
        assertEquals("已更新。任务清单（0/2 完成）：\n[>] 读取\n[ ] 修改", result.content());
        assertEquals(List.of("pre:todo_write", "post:todo_write"), events);
        assertFalse(output.contains("[y/N]"), output);
        assertEquals(2, todos.items().size());
    }

    @Test
    void reminderIsAppendedToLastToolResultAfterThreeStaleBatches() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("多步任务\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            int n = requests.size();
            return n <= 3 ? ChatMessage.assistant(null, List.of(
                    call("a" + n, "glob", Map.of("pattern", "*.txt")),
                    call("b" + n, "glob", Map.of("pattern", "*.md"))))
                    : new ChatMessage("assistant", "完成");
        }, 10);
        assertEquals(4, requests.size());
        List<ChatMessage> last = requests.get(3);
        assertEquals(List.of("system", "user", "assistant", "tool", "tool", "assistant", "tool", "tool",
                "assistant", "tool", "tool"), last.stream().map(ChatMessage::role).toList());
        assertEquals("b3", last.get(10).toolCallId());
        assertTrue(last.get(10).content().endsWith("\n\n" + TodoReminder.TEXT));
        assertFalse(last.get(9).content().contains("<reminder>"));
        assertFalse(containsReminder(requests.get(2)));
        assertEquals(1, output.split("提醒> ", -1).length - 1);
    }

    @Test
    void updatingTodosAvoidsReminderAndCompletedListStaysQuiet() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        run("任务\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            int n = requests.size();
            return switch (n) {
                case 1 -> ChatMessage.assistant(null, List.of(todo("t1", item("找文件", "in_progress"))));
                case 2, 3, 5, 6, 7 -> ChatMessage.assistant(null, List.of(glob("g" + n)));
                case 4 -> ChatMessage.assistant(null, List.of(todo("t2", item("找文件", "completed"))));
                default -> new ChatMessage("assistant", "完成");
            };
        }, 10);
        assertEquals(8, requests.size());
        assertFalse(containsReminder(requests.get(7)));
    }

    @Test
    void invalidTodoAndHookDenialLeaveListUnchanged() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        run("任务\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    todo("bad", item("一", "in_progress"), item("二", "in_progress")),
                    todo("blocked", item("被拦截", "pending"))))
                    : new ChatMessage("assistant", "完成");
        }, 10, new AgentHook() {
            @Override
            public String preToolUse(ToolCall call) {
                return "blocked".equals(call.id()) ? "禁止更新" : null;
            }
        });
        List<ChatMessage> second = requests.get(1);
        assertEquals("工具错误：同一时间只能有一项 in_progress。", second.get(3).content());
        assertTrue(second.get(4).content().startsWith("钩子拒绝：禁止更新"), second.get(4).content());
        assertTrue(todos.items().isEmpty());
        assertEquals(0, todos.version());
    }

    @Test
    void todosPersistAcrossTurnsAndReminderCounterResetsPerTurn() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        run("一\n二\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            int n = requests.size();
            return switch (n) {
                case 1 -> ChatMessage.assistant(null, List.of(todo("t", item("任务", "pending"))));
                case 2, 4, 5 -> ChatMessage.assistant(null, List.of(glob("g" + n)));
                case 3 -> new ChatMessage("assistant", "第一轮结束");
                default -> new ChatMessage("assistant", "第二轮结束");
            };
        }, 10);
        assertEquals(6, requests.size());
        assertEquals(1, todos.items().size());
        // 第一轮遗留 1 批未更新，第二轮再 2 批；按轮清零，因此仍无提醒。
        assertFalse(containsReminder(requests.get(5)));
    }

    @Test
    void reminderIsNotAddedWhenModelCallLimitIsReached() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("任务\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            return ChatMessage.assistant(null, List.of(glob("g" + requests.size())));
        }, 3);
        assertEquals(3, requests.size());
        assertFalse(output.contains("提醒> "), output);
        assertTrue(output.contains("已达到本轮模型调用上限"));
    }

    @Test
    void otherWriteToolsStillNeedPermission() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        run("写\nn\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    todo("t", item("写文件", "in_progress")),
                    call("w", "write_file", Map.of("path", "a.txt", "content", "x"))))
                    : new ChatMessage("assistant", "完成");
        }, 10);
        assertTrue(requests.get(1).get(4).content().startsWith("权限拒绝"), requests.get(1).get(4).content());
        assertFalse(Files.exists(root.resolve("work/a.txt")));
        assertEquals(1, todos.items().size());
    }

    private String run(String text, ModelClient client, int maxCalls, AgentHook... custom) throws IOException {
        BufferedReader input = new BufferedReader(new StringReader(text));
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake",
                Files.createDirectories(root.resolve("work")), maxCalls, Duration.ofSeconds(5), 12000);
        // 临时项目目录没有 hook.json，信任存储也在临时目录，不触碰真实用户记录。
        TodoMain.createLoop(config, client, input, output, Files.createDirectories(root.resolve("project")),
                List.of(custom), new HookTrustStore(root.resolve("trust")), todos).run(input, output);
        return buffer.toString();
    }

    private static boolean containsReminder(List<ChatMessage> messages) {
        return messages.stream().anyMatch(m -> m.content() != null && m.content().contains("<reminder>"));
    }

    private static Map<String, String> item(String content, String status) {
        return Map.of("content", content, "status", status);
    }

    @SafeVarargs
    private static ToolCall todo(String id, Map<String, String>... items) {
        return new ToolCall(id, "todo_write", JSON.toJSONString(Map.of("todos", List.of(items))));
    }

    private static ToolCall glob(String id) {
        return call(id, "glob", Map.of("pattern", "*"));
    }

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return new ToolCall(id, name, JSON.toJSONString(args));
    }
}
