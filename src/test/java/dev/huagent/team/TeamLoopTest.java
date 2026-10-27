package dev.huagent.team;

import dev.huagent.app.TeamMain;
import dev.huagent.app.ScheduleMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.skill.SkillCatalog;
import dev.huagent.task.TaskRecord;
import dev.huagent.task.TaskStatus;
import dev.huagent.task.TaskStore;
import dev.huagent.todo.TodoList;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 通过 TeamMain 组装的完整会话：主线程是唯一读取终端的线程，队友确认和结果都经由它。 */
class TeamLoopTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:30Z");

    @TempDir
    Path root;
    private Path work;
    private Path project;
    private final List<String> leadTools = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> teammateTools = new ConcurrentHashMap<>();
    private final List<List<ChatMessage>> leadRequests = new CopyOnWriteArrayList<>();
    private final List<List<ChatMessage>> teammateRequests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(root.resolve("work"));
        project = Files.createDirectories(root.resolve("project"));
    }

    @Test
    void teamDisabledByDefaultBehavesExactlyLikeS12() throws Exception {
        TaskStore tasks = new TaskStore(work.resolve(TaskStore.DIRECTORY_NAME));
        TaskRecord held = tasks.create("上次队友的", null);
        tasks.claim(held.id(), "alice", true);
        MessageBus bus = new MessageBus(work.resolve(MessageBus.DIRECTORY_NAME), to -> { });
        bus.send(new TeamMessage("alice", "lead", TeamMessage.RESULT, "旧结果"));
        StringWriter buffer = new StringWriter();
        TeamMain.Session session = session(new BufferedReader(new StringReader("x\nexit\n")), buffer,
                (messages, tools) -> {
                    leadTools.addAll(tools.stream().map(ToolDefinition::name).toList());
                    leadRequests.add(messages);
                    return new ChatMessage("assistant", "ok");
                }, false);
        assertFalse(session.teamEnabled());
        session.run();
        assertEquals(26, leadTools.size(), "只有 S12 的工具");
        assertTrue(leadTools.stream().noneMatch(TeamTools.LEAD_NAMES::contains));
        assertEquals(ScheduleMain.systemPrompt(SkillCatalog.empty(), List.of()), leadRequests.get(0).get(0).content(),
                "系统提示词与 S12 完全相同，没有团队规则");
        assertEquals("alice", tasks.load(held.id()).owner(), "关闭时不碰任务");
        assertFalse(bus.drain("lead").isEmpty(), "关闭时不清空收件箱");
        assertFalse(buffer.toString().contains("团队>"), buffer.toString());
    }

    @Test
    void leadHasS12ToolsPlusTeamToolsAndPromptAddsTeamRule() throws Exception {
        StringWriter buffer = new StringWriter();
        TeamMain.Session session = session("x\nexit\n", buffer, (messages, tools) -> {
            leadTools.addAll(tools.stream().map(ToolDefinition::name).toList());
            leadRequests.add(messages);
            return new ChatMessage("assistant", "ok");
        });
        session.run();
        assertEquals(TeamTools.LEAD_NAMES, leadTools.subList(leadTools.size() - 4, leadTools.size()));
        assertEquals(30, leadTools.size());
        assertEquals(ScheduleMain.systemPrompt(SkillCatalog.empty(), List.of()) + "\n\n" + TeamMain.TEAM_RULE,
                leadRequests.get(0).get(0).content());
    }

    @Test
    void spawnNeedsConfirmationAndDenialCreatesNoTeammate() throws Exception {
        StringWriter buffer = new StringWriter();
        TeamMain.Session session = session("组队\nn\nexit\n", buffer, lead(
                ChatMessage.assistant(null, List.of(call("s1", TeamTools.SPAWN,
                        Map.of("name", "alice", "role", "调研", "prompt", "看看"))))
                , new ChatMessage("assistant", "好的，不组队")));
        session.run();
        String text = buffer.toString();
        assertTrue(text.contains("权限确认> 将创建一个常驻队友线程"), text);
        assertTrue(text.contains("权限拒绝：用户未批准本次调用。"), text);
        assertTrue(session.team().names().isEmpty());
    }

    @Test
    void teammateIsFullAgentConfirmsOnMainTerminalAndResultWakesLead() throws Exception {
        PipedWriter keyboard = new PipedWriter();
        StringWriter buffer = new StringWriter();
        CompletableFuture<Void> leadWoken = new CompletableFuture<>();
        ModelClient client = (messages, tools) -> {
            List<String> names = tools.stream().map(ToolDefinition::name).toList();
            String system = messages.get(0).content();
            if (system.contains("你的名字是 alice")) {
                teammateTools.put("alice", names);
                teammateRequests.add(messages);
                ChatMessage last = messages.get(messages.size() - 1);
                if (!"tool".equals(last.role())) {
                    return ChatMessage.assistant(null, List.of(call("t1", "shell",
                            Map.of("command", "echo teammate-ran"))));
                }
                return new ChatMessage("assistant", "alice 完成：" + last.content().strip());
            }
            leadRequests.add(messages);
            ChatMessage latest = messages.get(messages.size() - 1);
            if ("user".equals(latest.role()) && latest.content().startsWith("<team-events>")) {
                leadWoken.complete(null);
                return new ChatMessage("assistant", "已汇总队友结果");
            }
            if ("tool".equals(messages.get(messages.size() - 1).role())) {
                return new ChatMessage("assistant", "已安排 alice");
            }
            return ChatMessage.assistant(null, List.of(call("s1", TeamTools.SPAWN,
                    Map.of("name", "alice", "role", "执行者", "prompt", "运行 echo"))));
        };
        TeamMain.Session session = session(new BufferedReader(new PipedReader(keyboard)), buffer, client);
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            try {
                session.run();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        type(keyboard, "请组队运行 echo");
        awaitText(buffer, "仅允许本次调用？");
        type(keyboard, "y");
        // 队友的 Shell 确认出现在主终端，由用户在这里回答。
        awaitText(buffer, "允许队友 alice 本次调用？");
        assertTrue(buffer.toString().contains("权限确认> [alice]"), buffer.toString());
        type(keyboard, "y");
        // 用户没有再输入，Lead 被队友结果唤醒。
        leadWoken.get(15, TimeUnit.SECONDS);
        String events = leadRequests.get(leadRequests.size() - 1)
                .get(leadRequests.get(leadRequests.size() - 1).size() - 1).content();
        assertTrue(events.contains("[result] alice：alice 完成：退出码：0"), events);
        assertTrue(events.contains("teammate-ran"), events);
        type(keyboard, "exit");
        running.get(15, TimeUnit.SECONDS);

        List<String> tools = teammateTools.get("alice");
        assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob", "todo_write", "task",
                "load_skill", "compact", "memory_read", "task_list", "task_get", "task_claim", "task_complete",
                "send_message"), tools);
        String system = teammateRequests.get(0).get(0).content();
        assertTrue(system.contains(TeamMain.TEAMMATE_RULE), system);
        assertTrue(system.contains("角色：执行者"), system);
        assertTrue(buffer.toString().contains("  [alice] 钩子 PreToolUse> shell"), "队友也经过 Hook 链");
        assertTrue(buffer.toString().contains("团队> 会话结束，已停止 1 个队友。"), buffer.toString());
    }

    @Test
    void exitStopsTeammatesBlockedOnConfirmationAndReleasesTheirTasks() throws Exception {
        TaskStore tasks = new TaskStore(work.resolve(TaskStore.DIRECTORY_NAME));
        TaskRecord task = tasks.create("写文件", null);
        PipedWriter keyboard = new PipedWriter();
        StringWriter buffer = new StringWriter();
        ModelClient client = (messages, tools) -> {
            if (messages.get(0).content().contains("你的名字是 bob")) {
                return ChatMessage.assistant(null, List.of(call("w1", "write_file",
                        Map.of("path", "out.txt", "content", "x"))));
            }
            if ("tool".equals(messages.get(messages.size() - 1).role())) {
                return new ChatMessage("assistant", "已安排");
            }
            return ChatMessage.assistant(null, List.of(call("s1", TeamTools.SPAWN,
                    Map.of("name", "bob", "role", "写手", "prompt", "写文件", "task_id", task.id()))));
        };
        TeamMain.Session session = session(new BufferedReader(new PipedReader(keyboard)), buffer, client);
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            try {
                session.run();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        type(keyboard, "安排 bob");
        awaitText(buffer, "仅允许本次调用？");
        type(keyboard, "y");
        awaitText(buffer, "允许队友 bob 本次调用？");
        assertEquals("bob", tasks.load(task.id()).owner());
        // 用户不回答队友的确认，而是直接结束输入。
        keyboard.close();
        running.get(15, TimeUnit.SECONDS);
        assertFalse(Files.exists(work.resolve("out.txt")), "未批准的写入没有执行");
        assertEquals(TaskStatus.PENDING, tasks.load(task.id()).status());
        assertTrue(session.team().names().isEmpty());
    }

    @Test
    void startupReleasesTasksHeldByTeammatesOfPreviousRunAndClearsInboxes() throws Exception {
        TaskStore tasks = new TaskStore(work.resolve(TaskStore.DIRECTORY_NAME));
        TaskRecord orphan = tasks.create("孤儿", null);
        TaskRecord mine = tasks.create("Lead 的", null);
        tasks.claim(orphan.id(), "alice", true);
        tasks.claim(mine.id(), "agent");
        MessageBus bus = new MessageBus(work.resolve(MessageBus.DIRECTORY_NAME), to -> { });
        bus.send(new TeamMessage("alice", "lead", TeamMessage.RESULT, "旧结果"));
        StringWriter buffer = new StringWriter();
        session("exit\n", buffer, lead()).run();
        String text = buffer.toString();
        assertTrue(text.contains("任务> " + orphan.id() + " 上次由已不存在的队友认领，已退回 pending。"), text);
        assertTrue(text.contains("团队> 已清空上次会话遗留的 1 个收件箱。"), text);
        assertEquals(TaskStatus.PENDING, tasks.load(orphan.id()).status());
        assertEquals("agent", tasks.load(mine.id()).owner());
        assertFalse(text.contains("旧结果"));
    }

    private TeamMain.Session session(String input, StringWriter buffer, ModelClient client) throws IOException {
        return session(new BufferedReader(new StringReader(input)), buffer, client);
    }

    private TeamMain.Session session(BufferedReader input, StringWriter buffer, ModelClient client)
            throws IOException {
        return session(input, buffer, client, true);
    }

    private TeamMain.Session session(BufferedReader input, StringWriter buffer, ModelClient client, boolean team)
            throws IOException {
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake", work, 6,
                Duration.ofSeconds(10), 12000, 50000, 3, Duration.ofSeconds(30)).withTeamEnabled(team);
        return TeamMain.createSession(config, client, input, new PrintWriter(buffer, true), project, List.of(),
                new HookTrustStore(root.resolve("trust")), new TodoList(), ZoneOffset.UTC,
                Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofSeconds(5));
    }

    private static ModelClient lead(ChatMessage... answers) {
        return new ModelClient() {
            int next;

            @Override
            public synchronized ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                return next < answers.length ? answers[next++] : new ChatMessage("assistant", "完成");
            }
        };
    }

    private static void type(PipedWriter keyboard, String line) throws IOException {
        keyboard.write(line + "\n");
        keyboard.flush();
    }

    private static void awaitText(StringWriter buffer, String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        int seen = count(buffer.toString(), text);
        while (true) {
            String current = buffer.toString();
            if (current.contains(text) && count(current, text) >= Math.max(1, seen)) {
                return;
            }
            if (System.nanoTime() > deadline) {
                fail("没有出现：" + text + "\n" + current);
            }
            Thread.sleep(10);
        }
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    private static ToolCall call(String id, String name, Map<String, ?> args) {
        return new ToolCall(id, name, JSON.toJSONString(args));
    }
}
