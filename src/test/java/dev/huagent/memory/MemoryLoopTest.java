package dev.huagent.memory;

import dev.huagent.app.MemoryMain;
import dev.huagent.app.CompactMain;
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

class MemoryLoopTest {
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
    void registersMemoryToolsAndPromptHasRuleWithoutIndexWhenEmpty() throws Exception {
        String output = run("x\nexit\n", script(new ChatMessage("assistant", "ok")));
        assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob", "grep", "todo_write", "task",
                "load_skill", "compact", "memory_read", "memory_write", "memory_delete"), toolNames.get(0));
        String system = requests.get(0).get(0).content();
        assertEquals(CompactMain.systemPrompt(SkillCatalog.empty()) + "\n\n" + MemoryMain.MEMORY_RULE, system);
        assertTrue(output.contains("记忆> 已加载索引 0 条：无"), output);
        assertFalse(Files.exists(work.resolve(MemoryStore.DIRECTORY_NAME)), "未写入时不创建目录");
    }

    @Test
    void memoryWrittenInOneSessionAppearsInNextSessionIndexAndReadsOnDemand() throws Exception {
        // 第一次会话：模型保存记忆，用户确认 y。
        String first = run("以后测试都用 JUnit 5\ny\nexit\n", script(
                ChatMessage.assistant(null, List.of(writeCall("w1", "prefer-junit5"))),
                new ChatMessage("assistant", "记住了")));
        assertTrue(first.contains("权限确认> 将保存跨会话记忆"), first);
        String written = contentOf(requests.get(1), "w1");
        assertTrue(written.startsWith("已新建记忆 prefer-junit5"), written);
        // 本次会话的系统提示词不变，写入不会改动已发送的上下文。
        assertEquals(requests.get(0).get(0).content(), requests.get(1).get(0).content());
        assertTrue(Files.isRegularFile(work.resolve(MemoryStore.DIRECTORY_NAME).resolve("prefer-junit5.md")));

        // 第二次会话：全新历史，索引出现在系统提示词，正文要读取才出现；读取免确认。
        requests.clear();
        String second = run("写个测试\nexit\n", script(
                ChatMessage.assistant(null, List.of(new ToolCall("r1", "memory_read",
                        JSON.toJSONString(Map.of("name", "prefer-junit5"))))),
                new ChatMessage("assistant", "用 JUnit 5")));
        assertTrue(second.contains("记忆> 已加载索引 1 条：prefer-junit5"), second);
        List<ChatMessage> start = requests.get(0);
        assertEquals(List.of("system", "user"), start.stream().map(ChatMessage::role).toList());
        String system = start.get(0).content();
        assertTrue(system.endsWith("- prefer-junit5 [feedback]：测试统一用 JUnit 5"), system);
        assertFalse(system.contains("BODY-JUNIT"), "正文不预先放进系统提示词");
        assertFalse(second.contains("[y/N]"), second);
        assertTrue(contentOf(requests.get(1), "r1").contains("BODY-JUNIT"));
    }

    @Test
    void deniedWriteSavesNothingAndDeleteNeedsConfirmation() throws Exception {
        String output = run("记住这个\nn\nexit\n", script(
                ChatMessage.assistant(null, List.of(writeCall("w1", "note"))),
                new ChatMessage("assistant", "好")));
        assertTrue(contentOf(requests.get(1), "w1").startsWith("权限拒绝：用户未批准"), output);
        assertFalse(Files.exists(work.resolve(MemoryStore.DIRECTORY_NAME).resolve("note.md")));

        new MemoryStore(work.resolve(MemoryStore.DIRECTORY_NAME)).write("old", MemoryType.PROJECT, "过时", "x");
        requests.clear();
        String deleted = run("删掉旧的\ny\nexit\n", script(
                ChatMessage.assistant(null, List.of(new ToolCall("d1", "memory_delete", "{\"name\":\"old\"}"))),
                new ChatMessage("assistant", "删了")));
        assertTrue(deleted.contains("权限确认> 将永久删除"), deleted);
        assertFalse(Files.exists(work.resolve(MemoryStore.DIRECTORY_NAME).resolve("old.md")));
    }

    @Test
    void memoryToolsPassThroughHooksAndCanBeBlocked() throws Exception {
        new MemoryStore(work.resolve(MemoryStore.DIRECTORY_NAME)).write("a", MemoryType.USER, "d", "正文");
        List<String> events = new ArrayList<>();
        AgentHook hook = new AgentHook() {
            @Override
            public String preToolUse(ToolCall call) {
                events.add("pre:" + call.name());
                return "memory_write".equals(call.name()) ? "禁止写记忆" : null;
            }

            @Override
            public void postToolUse(ToolCall call, String result, long elapsedNanos) {
                events.add("post:" + call.name());
            }
        };
        String output = run("x\nexit\n", script(
                ChatMessage.assistant(null, List.of(new ToolCall("r1", "memory_read", "{\"name\":\"a\"}"),
                        writeCall("w1", "b"))),
                new ChatMessage("assistant", "好")), hook);
        assertEquals(List.of("pre:memory_read", "post:memory_read", "pre:memory_write"), events);
        assertTrue(contentOf(requests.get(1), "w1").startsWith("钩子拒绝：禁止写记忆"));
        assertFalse(output.contains("[y/N]"), "Hook 拒绝后不再询问权限");
        assertFalse(Files.exists(work.resolve(MemoryStore.DIRECTORY_NAME).resolve("b.md")));
    }

    @Test
    void subagentHasNoMemoryTools() throws Exception {
        run("派个子任务\nexit\n", script(
                ChatMessage.assistant(null, List.of(new ToolCall("t1", "task",
                        JSON.toJSONString(Map.of("description", "看看", "prompt", "随便看看"))))),
                new ChatMessage("assistant", "子代理总结"),
                new ChatMessage("assistant", "完成")));
        assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob", "grep", "load_skill"),
                toolNames.get(1));
    }

    @Test
    void invalidMemoryFileIsSkippedAtStartup() throws Exception {
        Path dir = Files.createDirectories(work.resolve(MemoryStore.DIRECTORY_NAME));
        Files.writeString(dir.resolve("broken.md"), "没有 frontmatter");
        String output = run("x\nexit\n", script(new ChatMessage("assistant", "ok")));
        assertTrue(output.contains("记忆> 跳过 broken.md"), output);
        assertTrue(output.contains("记忆> 已加载索引 0 条"), output);
    }

    private static ToolCall writeCall(String id, String name) {
        return new ToolCall(id, "memory_write", JSON.toJSONString(Map.of("name", name, "type", "feedback",
                "description", "测试统一用 JUnit 5", "content", "BODY-JUNIT：原因是项目统一")));
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
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake", work, 5,
                Duration.ofSeconds(5), 12000);
        // 临时项目目录没有 hook.json 和技能，信任存储也在临时目录。
        MemoryMain.createLoop(config, client, input, output, Files.createDirectories(root.resolve("project")),
                List.of(hooks), new HookTrustStore(root.resolve("trust")), new TodoList()).run(input, output);
        return buffer.toString();
    }

    private static String contentOf(List<ChatMessage> messages, String callId) {
        return messages.stream().filter(m -> callId.equals(m.toolCallId())).findFirst().orElseThrow().content();
    }
}
