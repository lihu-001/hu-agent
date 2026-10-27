package dev.huagent.subagent;

import dev.huagent.app.SubagentMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.todo.TodoList;
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

class SubagentLoopTest {
    @TempDir
    Path root;
    private final TodoList todos = new TodoList();

    @Test
    void parentSeesOnlySummaryAndChildHasIsolatedHistoryWithoutTaskOrTodo() throws Exception {
        List<List<ChatMessage>> parent = new ArrayList<>();
        List<List<ChatMessage>> child = new ArrayList<>();
        List<String> events = new ArrayList<>();
        Files.writeString(Files.createDirectories(root.resolve("work")).resolve("a.txt"), "secret-content");
        run("调研\nexit\n", (messages, definitions) -> {
            List<String> names = definitions.stream().map(ToolDefinition::name).toList();
            if (messages.get(0).content().equals(SubagentMain.SUBAGENT_PROMPT)) {
                assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob"), names);
                child.add(messages);
                return child.size() == 1
                        ? ChatMessage.assistant(null, List.of(call("r", "read_file", Map.of("path", "a.txt"))))
                        : new ChatMessage("assistant", "a.txt 内容已确认");
            }
            assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob", "todo_write", "task"), names);
            parent.add(messages);
            return parent.size() == 1 ? ChatMessage.assistant(null, List.of(task("t1", "读文件", "读取 a.txt")))
                    : new ChatMessage("assistant", "完成");
        }, new AgentHook() {
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
        assertEquals(2, child.size());
        assertEquals(List.of("system", "user"), child.get(0).stream().map(ChatMessage::role).toList());
        assertEquals("读取 a.txt", child.get(0).get(1).content());
        assertFalse(child.get(0).stream().anyMatch(m -> "调研".equals(m.content())));
        assertTrue(child.get(1).get(3).content().contains("secret-content"));

        List<ChatMessage> second = parent.get(1);
        assertEquals(List.of("system", "user", "assistant", "tool"), second.stream().map(ChatMessage::role).toList());
        assertEquals("t1", second.get(3).toolCallId());
        assertEquals("子代理结果（模型请求 2 次，工具调用 1 个）：\na.txt 内容已确认", second.get(3).content());
        assertFalse(second.stream().anyMatch(m -> m.content() != null && m.content().contains("secret-content")));
        // 父代理的 task 包住子代理的 read_file：Hook 两层都触发。
        assertEquals(List.of("pre:task", "pre:read_file", "post:read_file", "post:task"), events);
    }

    @Test
    void childWritesStillRequireConfirmation() throws Exception {
        List<List<ChatMessage>> parent = new ArrayList<>();
        String output = run("写\nn\nexit\n", (messages, definitions) -> {
            if (messages.get(0).content().equals(SubagentMain.SUBAGENT_PROMPT)) {
                return messages.size() == 2 ? ChatMessage.assistant(null, List.of(
                        call("w", "write_file", Map.of("path", "b.txt", "content", "x"))))
                        : new ChatMessage("assistant", "写入被拒绝：" + messages.get(3).content());
            }
            parent.add(messages);
            return parent.size() == 1 ? ChatMessage.assistant(null, List.of(task("t", "写文件", "写入 b.txt")))
                    : new ChatMessage("assistant", "完成");
        });
        assertTrue(output.contains("[y/N]"), output);
        assertFalse(Files.exists(root.resolve("work/b.txt")));
        assertTrue(parent.get(1).get(3).content().contains("写入被拒绝：权限拒绝"), parent.get(1).get(3).content());
    }

    @Test
    void hookCanBlockTaskWithoutStartingChild() throws Exception {
        List<List<ChatMessage>> parent = new ArrayList<>();
        run("派发\nexit\n", (messages, definitions) -> {
            assertNotEquals(SubagentMain.SUBAGENT_PROMPT, messages.get(0).content(), "子代理不应启动");
            parent.add(messages);
            return parent.size() == 1 ? ChatMessage.assistant(null, List.of(task("t", "x", "y")))
                    : new ChatMessage("assistant", "完成");
        }, new AgentHook() {
            @Override
            public String preToolUse(ToolCall call) {
                return "task".equals(call.name()) ? "禁止派发" : null;
            }
        });
        assertTrue(parent.get(1).get(3).content().startsWith("钩子拒绝：禁止派发"));
    }

    @Test
    void childModelFailureIsReturnedAsToolErrorAndParentContinues() throws Exception {
        List<List<ChatMessage>> parent = new ArrayList<>();
        run("派发\nexit\n", (messages, definitions) -> {
            if (messages.get(0).content().equals(SubagentMain.SUBAGENT_PROMPT)) {
                throw new IOException("boom");
            }
            parent.add(messages);
            return parent.size() == 1 ? ChatMessage.assistant(null, List.of(task("t", "x", "y")))
                    : new ChatMessage("assistant", "完成");
        });
        assertEquals(2, parent.size());
        assertTrue(parent.get(1).get(3).content().startsWith("工具错误：子代理模型请求失败"));
    }

    @Test
    void childCannotChangeParentTodos() throws Exception {
        run("派发\nexit\n", (messages, definitions) -> {
            if (messages.get(0).content().equals(SubagentMain.SUBAGENT_PROMPT)) {
                // 子代理即使尝试调用 todo_write，也只会得到未知工具错误。
                return messages.size() == 2 ? ChatMessage.assistant(null, List.of(new ToolCall("x", "todo_write",
                        JSON.toJSONString(Map.of("todos", List.of(Map.of("content", "a", "status", "pending")))))))
                        : new ChatMessage("assistant", messages.get(3).content());
            }
            return messages.size() == 2 ? ChatMessage.assistant(null, List.of(task("t", "x", "y")))
                    : new ChatMessage("assistant", "完成");
        });
        assertTrue(todos.items().isEmpty());
    }

    private String run(String text, ModelClient client, AgentHook... custom) throws IOException {
        BufferedReader input = new BufferedReader(new StringReader(text));
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake",
                Files.createDirectories(root.resolve("work")), 5, Duration.ofSeconds(5), 12000);
        // 临时项目目录没有 hook.json，信任存储也在临时目录，不触碰真实用户记录。
        SubagentMain.createLoop(config, client, input, output, Files.createDirectories(root.resolve("project")),
                List.of(custom), new HookTrustStore(root.resolve("trust")), todos).run(input, output);
        return buffer.toString();
    }

    private static ToolCall task(String id, String description, String prompt) {
        return call(id, "task", Map.of("description", description, "prompt", prompt));
    }

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return new ToolCall(id, name, JSON.toJSONString(args));
    }
}
