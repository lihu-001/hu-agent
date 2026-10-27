package dev.huagent.task;

import dev.huagent.app.TaskMain;
import dev.huagent.app.MemoryMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.skill.SkillCatalog;
import dev.huagent.todo.TodoList;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
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

class TaskLoopTest {
    @TempDir
    Path root;
    private Path work;
    private final List<List<ChatMessage>> requests = new ArrayList<>();
    private final List<List<String>> toolNames = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(root.resolve("work"));
    }

    @Test
    void registersTaskToolsAndPromptAddsTaskRule() throws Exception {
        String output = run("x\nexit\n", script(new ChatMessage("assistant", "ok")));
        assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob", "todo_write", "task",
                "load_skill", "compact", "memory_read", "memory_write", "memory_delete", "task_create",
                "task_update", "task_list", "task_get", "task_claim", "task_complete"), toolNames.get(0));
        assertEquals(MemoryMain.systemPrompt(SkillCatalog.empty(), List.of()) + "\n\n" + TaskMain.TASK_RULE,
                requests.get(0).get(0).content());
        assertTrue(output.contains("任务> .tasks 中有 0 个任务，已完成 0 个"), output);
        assertFalse(Files.exists(work.resolve(TaskStore.DIRECTORY_NAME)), "未创建任务时不建目录");
    }

    @Test
    void tasksCreatedInOneSessionResumeInNextWithoutConfirmation() throws Exception {
        TaskStore store = new TaskStore(work.resolve(TaskStore.DIRECTORY_NAME));
        // 第一次会话：模型并行创建两个节点，下一批用返回的 ID 建立依赖，再认领并完成第一个。
        String first = run("拆任务\nexit\n", new ModelClient() {
            int next;
            String schema;
            String api;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                requests.add(messages);
                toolNames.add(tools.stream().map(ToolDefinition::name).toList());
                switch (next++) {
                    case 0:
                        return ChatMessage.assistant(null, List.of(
                                call("c1", "task_create", Map.of("subject", "建表")),
                                call("c2", "task_create", Map.of("subject", "接口"))));
                    case 1:
                        schema = TaskToolsTest.idOf(contentOf(messages, "c1"));
                        api = TaskToolsTest.idOf(contentOf(messages, "c2"));
                        return ChatMessage.assistant(null, List.of(
                                call("u1", "task_update", Map.of("id", api, "addBlockedBy", List.of(schema))),
                                call("k1", "task_claim", Map.of("id", api)),
                                call("k2", "task_claim", Map.of("id", schema))));
                    case 2:
                        assertTrue(contentOf(messages, "k1").startsWith("工具错误：任务 " + api + " 仍被未完成"),
                                contentOf(messages, "k1"));
                        return ChatMessage.assistant(null, List.of(call("d1", "task_complete", Map.of("id", schema))));
                    case 3:
                        assertTrue(contentOf(messages, "d1").contains("已解锁：" + api), contentOf(messages, "d1"));
                        return new ChatMessage("assistant", "建表完成");
                    default:
                        throw new AssertionError();
                }
            }
        });
        assertFalse(first.contains("[y/N]"), "任务工具免确认：" + first);
        List<TaskRecord> saved = store.list(w -> fail(w));
        assertEquals(2, saved.size());

        // 第二次会话：全新历史，系统提示词不含任务内容，task_list 从文件恢复进度。
        requests.clear();
        String second = run("继续\nexit\n", script(
                ChatMessage.assistant(null, List.of(new ToolCall("l1", "task_list", "{}"))),
                new ChatMessage("assistant", "接下来做接口")));
        assertTrue(second.contains("任务> .tasks 中有 2 个任务，已完成 1 个"), second);
        assertFalse(requests.get(0).get(0).content().contains("建表"), "任务内容不进系统提示词");
        String listed = contentOf(requests.get(1), "l1");
        assertTrue(listed.contains("[x]") && listed.contains("建表 [completed] 认领者=agent"), listed);
        assertTrue(listed.contains("接口 [pending]") && listed.contains("可开始"), listed);
    }

    @Test
    void taskToolsPassThroughHooksAndCanBeBlocked() throws Exception {
        List<String> events = new ArrayList<>();
        AgentHook hook = new AgentHook() {
            @Override
            public String preToolUse(ToolCall call) {
                events.add("pre:" + call.name());
                return "task_create".equals(call.name()) ? "禁止建任务" : null;
            }

            @Override
            public void postToolUse(ToolCall call, String result, long elapsedNanos) {
                events.add("post:" + call.name());
            }
        };
        run("x\nexit\n", script(
                ChatMessage.assistant(null, List.of(new ToolCall("l1", "task_list", "{}"),
                        call("c1", "task_create", Map.of("subject", "a")))),
                new ChatMessage("assistant", "好")), hook);
        assertEquals(List.of("pre:task_list", "post:task_list", "pre:task_create"), events);
        assertTrue(contentOf(requests.get(1), "c1").startsWith("钩子拒绝：禁止建任务"));
        assertFalse(Files.exists(work.resolve(TaskStore.DIRECTORY_NAME)));
    }

    @Test
    void subagentHasNoTaskTools() throws Exception {
        run("派个子任务\nexit\n", script(
                ChatMessage.assistant(null, List.of(call("t1", "task", Map.of("description", "看看", "prompt", "随便看看")))),
                new ChatMessage("assistant", "子代理总结"),
                new ChatMessage("assistant", "完成")));
        assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob", "load_skill"),
                toolNames.get(1));
    }

    @Test
    void invalidTaskFileIsReportedAtStartup() throws Exception {
        Path dir = Files.createDirectories(work.resolve(TaskStore.DIRECTORY_NAME));
        Files.writeString(dir.resolve("task_12345678.json"), "坏文件");
        String output = run("x\nexit\n", script(new ChatMessage("assistant", "ok")));
        assertTrue(output.contains("任务> 跳过 task_12345678.json"), output);
        assertTrue(output.contains("任务> .tasks 中有 0 个任务"), output);
    }

    private static ToolCall call(String id, String name, Map<String, ?> args) {
        return new ToolCall(id, name, JSON.toJSONString(args));
    }

    private ModelClient script(ChatMessage... answers) {
        return new ModelClient() {
            int next;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                requests.add(messages);
                toolNames.add(tools.stream().map(ToolDefinition::name).toList());
                return answers[next++];
            }
        };
    }

    private String run(String text, ModelClient client, AgentHook... hooks) throws IOException {
        BufferedReader input = new BufferedReader(new StringReader(text));
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake", work, 6,
                Duration.ofSeconds(5), 12000);
        // 临时项目目录没有 hook.json 和技能，信任存储也在临时目录。
        TaskMain.createLoop(config, client, input, output, Files.createDirectories(root.resolve("project")),
                List.of(hooks), new HookTrustStore(root.resolve("trust")), new TodoList()).run(input, output);
        return buffer.toString();
    }

    private static String contentOf(List<ChatMessage> messages, String callId) {
        return messages.stream().filter(m -> callId.equals(m.toolCallId())).findFirst().orElseThrow().content();
    }
}
