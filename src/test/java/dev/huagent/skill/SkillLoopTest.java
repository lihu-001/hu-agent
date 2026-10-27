package dev.huagent.skill;

import dev.huagent.app.SkillMain;
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

class SkillLoopTest {
    @TempDir
    Path root;
    private Path project;

    @BeforeEach
    void setUp() throws IOException {
        project = Files.createDirectories(root.resolve("project"));
        Path file = Files.createDirectories(project.resolve(SkillCatalog.DEFAULT_PATH).resolve("greet"))
                .resolve(SkillCatalog.SKILL_FILE);
        Files.writeString(file, "---\nname: greet\ndescription: 打招呼的固定格式\n---\nGREET-BODY：先说你好\n");
    }

    @Test
    void systemPromptHasCatalogOnlyAndBodyArrivesAsToolResult() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        List<String> events = new ArrayList<>();
        String output = run("打个招呼\nexit\n", (messages, definitions) -> {
            assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob",
                    "todo_write", "task", "load_skill"), definitions.stream().map(ToolDefinition::name).toList());
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(load("s1", "greet")))
                    : new ChatMessage("assistant", "你好");
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
        String system = requests.get(0).get(0).content();
        assertTrue(system.startsWith(SubagentMain.SYSTEM_PROMPT));
        assertTrue(system.contains("- greet：打招呼的固定格式"), system);
        assertFalse(system.contains("GREET-BODY"), "正文不应预先放进系统提示词");
        assertFalse(requests.get(0).stream().anyMatch(m -> m.content() != null && m.content().contains("GREET-BODY")));

        List<ChatMessage> second = requests.get(1);
        assertEquals(List.of("system", "user", "assistant", "tool"), second.stream().map(ChatMessage::role).toList());
        assertEquals("<skill name=\"greet\">\nGREET-BODY：先说你好\n</skill>", second.get(3).content());
        // load_skill 免确认，但仍经过 Hook。
        assertFalse(output.contains("[y/N]"), output);
        assertEquals(List.of("pre:load_skill", "post:load_skill"), events);
        assertTrue(output.contains("技能> 已加载目录 1 个：greet"), output);
    }

    @Test
    void unknownSkillBecomesToolErrorAndHookCanBlockLoading() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        run("x\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            return requests.size() == 1
                    ? ChatMessage.assistant(null, List.of(load("a", "missing"), load("b", "greet")))
                    : new ChatMessage("assistant", "完成");
        }, new AgentHook() {
            @Override
            public String preToolUse(ToolCall call) {
                return call.arguments().contains("greet") ? "禁止加载" : null;
            }
        });
        List<ChatMessage> second = requests.get(1);
        assertTrue(second.get(3).content().startsWith("工具错误：未知技能 missing"), second.get(3).content());
        assertTrue(second.get(4).content().startsWith("钩子拒绝：禁止加载"), second.get(4).content());
    }

    @Test
    void subagentSeesCatalogAndCanLoadSkillButStillHasNoTask() throws Exception {
        List<List<ChatMessage>> child = new ArrayList<>();
        List<List<ChatMessage>> parent = new ArrayList<>();
        run("派发\nexit\n", (messages, definitions) -> {
            List<String> names = definitions.stream().map(ToolDefinition::name).toList();
            if (messages.get(0).content().startsWith(SubagentMain.SUBAGENT_PROMPT)) {
                assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob", "load_skill"), names);
                assertTrue(messages.get(0).content().contains("- greet："));
                child.add(messages);
                return child.size() == 1 ? ChatMessage.assistant(null, List.of(load("c", "greet")))
                        : new ChatMessage("assistant", "子代理读到：" + messages.get(3).content());
            }
            parent.add(messages);
            return parent.size() == 1 ? ChatMessage.assistant(null, List.of(new ToolCall("t", "task",
                    JSON.toJSONString(Map.of("description", "招呼", "prompt", "按 greet 技能打招呼")))))
                    : new ChatMessage("assistant", "完成");
        });
        assertEquals(2, child.size());
        assertTrue(parent.get(1).get(3).content().contains("GREET-BODY"));
        // 主代理历史里只有子代理总结，没有子代理的 load_skill 调用。
        assertFalse(parent.get(1).stream().anyMatch(m -> m.toolCalls().stream()
                .anyMatch(call -> "load_skill".equals(call.name()))));
    }

    @Test
    void withoutSkillsPromptEqualsPreviousLesson() throws Exception {
        Files.walk(project.resolve(".agents")).sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
            try {
                Files.delete(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        List<String> systems = new ArrayList<>();
        String output = run("x\nexit\n", (messages, definitions) -> {
            systems.add(messages.get(0).content());
            return new ChatMessage("assistant", "ok");
        });
        assertEquals(SubagentMain.SYSTEM_PROMPT, systems.get(0));
        assertTrue(output.contains("技能> 已加载目录 0 个：无"), output);
    }

    @Test
    void invalidSkillWarnsButStarts() throws Exception {
        Path bad = Files.createDirectories(project.resolve(SkillCatalog.DEFAULT_PATH).resolve("bad"))
                .resolve(SkillCatalog.SKILL_FILE);
        Files.writeString(bad, "没有 frontmatter");
        String output = run("exit\n", (messages, definitions) -> fail("不应请求模型"));
        assertTrue(output.contains("技能> 跳过 bad："), output);
        assertTrue(output.contains("技能> 已加载目录 1 个：greet"), output);
    }

    private String run(String text, ModelClient client, AgentHook... custom) throws IOException {
        BufferedReader input = new BufferedReader(new StringReader(text));
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake",
                Files.createDirectories(root.resolve("work")), 5, Duration.ofSeconds(5), 12000);
        // 临时项目目录没有 hook.json，信任存储也在临时目录，不触碰真实用户记录。
        SkillMain.createLoop(config, client, input, output, project, List.of(custom),
                new HookTrustStore(root.resolve("trust")), new TodoList()).run(input, output);
        return buffer.toString();
    }

    private static ToolCall load(String id, String name) {
        return new ToolCall(id, "load_skill", JSON.toJSONString(Map.of("name", name)));
    }
}
