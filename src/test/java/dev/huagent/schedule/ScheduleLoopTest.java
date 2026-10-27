package dev.huagent.schedule;

import dev.huagent.app.ScheduleMain;
import dev.huagent.app.BackgroundMain;
import dev.huagent.config.AgentConfig;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleLoopTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:30Z");
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    @TempDir
    Path root;
    private Path work;
    private Path project;
    private final List<List<ChatMessage>> requests = new ArrayList<>();
    private final List<List<String>> toolNames = new ArrayList<>();
    private StringWriter buffer;

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(root.resolve("work"));
        project = Files.createDirectories(root.resolve("project"));
    }

    @Test
    void registersScheduleToolsAndPromptAddsRule() throws Exception {
        run("x\nexit\n", script(new ChatMessage("assistant", "ok")));
        List<String> names = toolNames.get(0);
        assertEquals(ScheduleTools.NAMES, names.subList(names.size() - ScheduleTools.NAMES.size(), names.size()));
        assertEquals(26, names.size());
        assertEquals(BackgroundMain.systemPrompt(SkillCatalog.empty(), List.of()) + "\n\n"
                        + ScheduleMain.SCHEDULE_RULE,
                requests.get(0).get(0).content());
    }

    @Test
    void createsScheduleThroughToolAndPersistsIt() throws Exception {
        run("创建计划\ny\nexit\n", script(
                ChatMessage.assistant(null, List.of(call("c1", "schedule_create", Map.of(
                        "name", "每分钟检查", "cron", "* * * * *", "command", "echo ok")))),
                new ChatMessage("assistant", "已创建")));
        String text = buffer.toString();
        assertTrue(text.contains("权限确认>"), text);
        assertTrue(text.contains("已创建 schedule_"), text);

        List<String> warnings = new ArrayList<>();
        List<ScheduleRecord> schedules = new ScheduleStore(work.resolve(ScheduleStore.DIRECTORY_NAME))
                .list(warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(1, schedules.size());
        assertEquals("每分钟检查", schedules.get(0).name());
        assertEquals("echo ok", schedules.get(0).command());
        assertTrue(schedules.get(0).enabled());
        assertNotNull(schedules.get(0).nextRunAt());
    }

    @Test
    void dueScheduleRunsThroughBackgroundWithoutEnteringChatNotifications() throws Exception {
        ScheduleStore store = new ScheduleStore(work.resolve(ScheduleStore.DIRECTORY_NAME));
        ScheduleRecord schedule = store.create("回显", "* * * * *", "echo scheduled-ok",
                NOW.minusSeconds(120), UTC);

        ScheduleMain.Session session = session(script(new ChatMessage("assistant", "unused")));
        try {
            session.schedules().tick(NOW);
            ScheduleRecord finished = awaitStatus(store, schedule.id(), ScheduleRunStatus.COMPLETED);
            assertTrue(finished.lastOutput().contains("scheduled-ok"), finished.lastOutput());
            assertEquals(1, finished.runCount());
            assertTrue(session.background().list().isEmpty());
            assertTrue(session.background().takeNotifications().isEmpty());
        } finally {
            session.background().shutdown();
        }
    }

    @Test
    void subagentHasNoScheduleTools() throws Exception {
        run("派个子任务\nexit\n", script(
                ChatMessage.assistant(null, List.of(call("t1", "task", Map.of("description", "看看", "prompt", "随便看看")))),
                new ChatMessage("assistant", "子代理总结"),
                new ChatMessage("assistant", "完成")));
        assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob", "load_skill"),
                toolNames.get(1));
    }

    private ScheduleMain.Session session(ModelClient client) throws IOException {
        buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake", work, 6,
                Duration.ofSeconds(5), 12000, 50000, 3, Duration.ofSeconds(30));
        return ScheduleMain.createSession(config, client, new BufferedReader(new StringReader("exit\n")), output,
                project, List.of(), new HookTrustStore(root.resolve("trust")), new TodoList(), UTC,
                Clock.fixed(NOW, UTC), false);
    }

    private void run(String text, ModelClient client) throws IOException {
        buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake", work, 6,
                Duration.ofSeconds(5), 12000, 50000, 3, Duration.ofSeconds(30));
        BufferedReader input = new BufferedReader(new StringReader(text));
        ScheduleMain.createSession(config, client, input, output, project, List.of(),
                new HookTrustStore(root.resolve("trust")), new TodoList(), UTC,
                Clock.fixed(NOW, UTC), false).loop().run(input, output);
    }

    private ScheduleRecord awaitStatus(ScheduleStore store, String id, ScheduleRunStatus status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ScheduleRecord last = null;
        while (System.nanoTime() < deadline) {
            last = store.load(id);
            if (last.lastStatus() == status) {
                return last;
            }
            Thread.sleep(20);
        }
        fail("等待 " + status + " 超时，最后：" + last);
        return last;
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
}
