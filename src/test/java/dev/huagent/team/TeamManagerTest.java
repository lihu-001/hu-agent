package dev.huagent.team;

import dev.huagent.hook.AgentHooks;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.task.TaskRecord;
import dev.huagent.task.TaskStatus;
import dev.huagent.task.TaskStore;
import dev.huagent.task.TaskTools;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.tool.ToolRegistry;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** 队友线程、收件箱与任务认领的组合行为；模型是按名字分派的脚本，不请求真实服务。 */
class TeamManagerTest {
    @TempDir
    Path root;
    private TaskStore tasks;
    private MessageBus bus;
    private StringWriter buffer;
    private TeamManager team;
    /** 每个队友的脚本：收到请求消息，返回回答。 */
    private final Map<String, Function<List<ChatMessage>, ChatMessage>> scripts = new ConcurrentHashMap<>();
    private final Map<String, List<List<ChatMessage>>> requests = new ConcurrentHashMap<>();
    private final BlockingQueue<String> leadWakes = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() {
        tasks = new TaskStore(root.resolve(TaskStore.DIRECTORY_NAME));
        bus = new MessageBus(root.resolve(MessageBus.DIRECTORY_NAME), to -> {
            if (TeamManager.LEAD.equals(to)) {
                leadWakes.add(to);
            }
        });
        buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        team = new TeamManager(bus, tasks, (name, role, worktree, manager, memberOutput) -> {
            List<AgentTool> tools = new ArrayList<>(TaskTools.createForTeammate(tasks, name));
            tools.addAll(TeamTools.forTeammate(manager, name));
            ToolRegistry registry = new ToolRegistry(tools);
            ModelClient client = (messages, definitions) -> {
                requests.computeIfAbsent(name, key -> new CopyOnWriteArrayList<>()).add(messages);
                return scripts.getOrDefault(name, m -> new ChatMessage("assistant", "完成")).apply(messages);
            };
            return new TeammateSetup(client, registry, "队友 " + name + "：" + role, AgentHooks.empty(), null, null,
                    6, memberOutput);
        }, output, Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        team.shutdownAll();
    }

    @Test
    void spawnRunsTeammateOnItsOwnThreadAndResultReachesLead() throws Exception {
        team.spawn("alice", "调研", "看一下 README", null);
        List<TeamMessage> mail = awaitLeadMail(1);
        assertEquals(TeamMessage.RESULT, mail.get(0).type());
        assertEquals("alice", mail.get(0).from());
        assertEquals("完成", mail.get(0).content());
        List<ChatMessage> first = requests.get("alice").get(0);
        assertEquals("队友 alice：调研", first.get(0).content());
        assertEquals("看一下 README", first.get(1).content());
        assertEquals(Teammate.Status.IDLE, awaitStatus("alice", Teammate.Status.IDLE));
        assertTrue(buffer.toString().contains("  [alice] 模型请求>"), "队友输出带名字前缀");
    }

    @Test
    void teammateKeepsHistoryAcrossTurnsAndMessagesWakeIt() throws Exception {
        team.spawn("alice", "调研", "第一件事", null);
        awaitLeadMail(1);
        team.send(TeamManager.LEAD, "alice", "第二件事");
        awaitLeadMail(1);
        List<ChatMessage> second = requests.get("alice").get(1);
        // 系统提示词、第一轮输入与回复、第二轮输入：常驻队友不重新开始。
        assertEquals(4, second.size());
        assertEquals("第一件事", second.get(1).content());
        assertTrue(second.get(3).content().contains("<team-events>"), second.get(3).content());
        assertTrue(second.get(3).content().contains("[message] lead：第二件事"), second.get(3).content());
    }

    @Test
    void teammatesMessageEachOtherDirectlyWithoutLead() throws Exception {
        scripts.put("alice", messages -> messages.size() == 2
                ? ChatMessage.assistant(null, List.of(call("s1", TeamTools.SEND,
                Map.of("to", "bob", "content", "接口定好了")))) : new ChatMessage("assistant", "已通知 bob"));
        team.spawn("bob", "前端", "等 alice 的接口", null);
        awaitLeadMail(1);
        team.spawn("alice", "后端", "定接口并通知 bob", null);
        List<TeamMessage> mail = awaitLeadMail(2);
        assertTrue(mail.stream().allMatch(m -> TeamMessage.RESULT.equals(m.type())), mail.toString());
        List<ChatMessage> bobSecond = requests.get("bob").get(1);
        String input = bobSecond.get(bobSecond.size() - 1).content();
        assertTrue(input.contains("[message] alice：接口定好了"), input);
    }

    @Test
    void spawnWithTaskClaimsBeforeStartAndRejectsOnFailure() throws Exception {
        TaskRecord task = tasks.create("写接口", "GET /users");
        team.spawn("alice", "后端", "开始吧", task.id());
        assertEquals("alice", tasks.load(task.id()).owner());
        awaitLeadMail(1);
        String first = requests.get("alice").get(0).get(1).content();
        assertTrue(first.startsWith("[已分配任务 " + task.id() + "] 写接口\nGET /users"), first);

        assertThrows(IllegalArgumentException.class, () -> team.spawn("bob", "x", "y", task.id()),
                "任务已被认领时不创建队友");
        assertNull(team.member("bob"));
    }

    @Test
    void idleTeammateAutoClaimsNewTaskWhenWoken() throws Exception {
        scripts.put("alice", messages -> {
            String input = messages.get(messages.size() - 1).content();
            if ("tool".equals(messages.get(messages.size() - 1).role())) {
                return new ChatMessage("assistant", "任务完成");
            }
            if (input.startsWith("[自动认领任务 ")) {
                String id = input.substring("[自动认领任务 ".length(), input.indexOf(']'));
                return ChatMessage.assistant(null, List.of(call("c1", TaskTools.COMPLETE, Map.of("id", id))));
            }
            return new ChatMessage("assistant", "待命");
        });
        team.spawn("alice", "通用", "待命", null);
        awaitLeadMail(1);
        TaskRecord task = tasks.create("新任务", null);
        bus.wakeAll();
        List<TeamMessage> mail = awaitLeadMail(1);
        assertEquals("任务完成", mail.get(0).content());
        assertEquals(TaskStatus.COMPLETED, tasks.load(task.id()).status());
        assertEquals("alice", tasks.load(task.id()).owner());
    }

    @Test
    void shutdownRequestIsAnsweredAfterTurnAndUnfinishedTaskIsReleased() throws Exception {
        TaskRecord task = tasks.create("长任务", null);
        team.spawn("alice", "后端", "开始", task.id());
        awaitLeadMail(1);
        assertEquals(TaskStatus.IN_PROGRESS, tasks.load(task.id()).status());
        String requestId = team.requestShutdown("alice");
        String events = awaitLeadEvents();
        assertTrue(events.contains("[shutdown_response " + requestId + "] alice：已停止。"), events);
        assertFalse(events.contains("已忽略"), events);
        awaitGone("alice");
        assertEquals(TaskStatus.PENDING, tasks.load(task.id()).status(), "退出时退回未完成的任务");
        assertThrows(IllegalArgumentException.class, () -> team.requestShutdown("alice"));
    }

    @Test
    void forgedShutdownResponseIsMarkedIgnored() throws Exception {
        team.spawn("alice", "x", "y", null);
        awaitLeadMail(1);
        bus.send(new TeamMessage("alice", "lead", TeamMessage.SHUTDOWN_RESPONSE, "我停了", "req_000000", null));
        String events = awaitLeadEvents();
        assertTrue(events.contains("（没有对应的关闭请求，已忽略）"), events);
        assertNotNull(team.member("alice"));
    }

    @Test
    void failedTurnIsReportedAsErrorAndHistoryKeepsInput() throws Exception {
        scripts.put("alice", messages -> {
            throw new IllegalStateException("脚本不应被调用");
        });
        team = new TeamManager(bus, tasks, (name, role, worktree, manager, memberOutput) -> new TeammateSetup(
                (messages, definitions) -> {
                    throw new IOException("服务不可用");
                }, new ToolRegistry(List.of()), "p", AgentHooks.empty(), null, null, 3, memberOutput),
                new PrintWriter(buffer), Duration.ofMillis(200));
        Teammate alice = team.spawn("alice", "x", "做点事", null);
        List<TeamMessage> mail = awaitLeadMail(1);
        assertEquals(TeamMessage.ERROR, mail.get(0).type());
        assertTrue(mail.get(0).content().contains("服务不可用"), mail.get(0).content());
        assertEquals("做点事", alice.history().get(1).content(), "收件箱里的输入不会因为请求失败而丢失");
    }

    @Test
    void crashingTeammateReportsStoppedAndReleasesTask() throws Exception {
        TaskRecord task = tasks.create("t", null);
        team = new TeamManager(bus, tasks, (name, role, worktree, manager, memberOutput) -> new TeammateSetup(
                (messages, definitions) -> {
                    throw new IllegalStateException("崩溃");
                }, new ToolRegistry(List.of()), "p", AgentHooks.empty(), null, null, 3, memberOutput),
                new PrintWriter(buffer), Duration.ofMillis(200));
        team.spawn("alice", "x", "y", task.id());
        List<TeamMessage> mail = awaitLeadMail(1);
        assertEquals(TeamMessage.STOPPED, mail.get(0).type());
        assertTrue(mail.get(0).content().contains("崩溃"), mail.get(0).content());
        awaitGone("alice");
        assertEquals(TaskStatus.PENDING, tasks.load(task.id()).status());
    }

    @Test
    void spawnValidatesNamesLimitsAndShutdownAllStopsEveryone() throws Exception {
        for (String name : List.of("lead", "agent", "Alice", "a b")) {
            assertThrows(IllegalArgumentException.class, () -> team.spawn(name, "r", "p", null), name);
        }
        assertThrows(IllegalArgumentException.class, () -> team.spawn("x", " ", "p", null));
        for (int i = 0; i < TeamManager.MAX_TEAMMATES; i++) {
            team.spawn("m" + i, "r", "p", null);
        }
        assertThrows(IllegalArgumentException.class, () -> team.spawn("m0", "r", "p", null));
        assertThrows(IllegalArgumentException.class, () -> team.spawn("extra", "r", "p", null));
        assertThrows(IllegalArgumentException.class, () -> team.send("lead", "nobody", "hi"));
        assertThrows(IllegalArgumentException.class, () -> team.send("m0", "m0", "hi"));
        awaitLeadMail(TeamManager.MAX_TEAMMATES);
        assertTrue(team.render().contains("m0（r） idle"), team.render());

        assertEquals(TeamManager.MAX_TEAMMATES, team.shutdownAll());
        for (int i = 0; i < TeamManager.MAX_TEAMMATES; i++) {
            awaitGone("m" + i);
        }
        assertNull(team.leadMail(), "会话结束时不再给 Lead 发消息");
        assertThrows(IllegalStateException.class, () -> team.spawn("late", "r", "p", null));
    }

    @Test
    void taskWakeHookFiresOnlyForGraphChanges() throws Exception {
        team.spawn("alice", "x", "y", null);
        awaitLeadMail(1);
        int before = requests.get("alice").size();
        var hook = team.taskWakeHook();
        hook.postToolUse(new ToolCall("1", "task_list", "{}"), "", 0);
        TaskRecord task = tasks.create("新", null);
        // 创建本身不唤醒，Hook 触发后空闲的 alice 去认领。
        hook.postToolUse(new ToolCall("2", TaskTools.CREATE, "{}"), "", 0);
        awaitLeadMail(1);
        assertTrue(requests.get("alice").size() > before);
        assertEquals("alice", tasks.load(task.id()).owner());
    }

    private List<TeamMessage> awaitLeadMail(int count) throws Exception {
        List<TeamMessage> mail = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (mail.size() < count) {
            if (System.nanoTime() > deadline) {
                fail("等待 Lead 收到 " + count + " 条消息超时：" + mail + "\n" + buffer);
            }
            leadWakes.poll(100, TimeUnit.MILLISECONDS);
            mail.addAll(bus.drain(TeamManager.LEAD));
        }
        return mail;
    }

    private String awaitLeadEvents() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            leadWakes.poll(100, TimeUnit.MILLISECONDS);
            String events = team.leadMail();
            if (events != null) {
                return events;
            }
        }
        fail("等待 Lead 消息超时\n" + buffer);
        return null;
    }

    private Teammate.Status awaitStatus(String name, Teammate.Status status) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Teammate member = team.member(name);
            if (member != null && member.status() == status) {
                return status;
            }
            Thread.sleep(10);
        }
        fail("等待 " + name + " 进入 " + status + " 超时");
        return null;
    }

    private void awaitGone(String name) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (team.member(name) != null) {
            if (System.nanoTime() > deadline) {
                fail(name + " 没有退出");
            }
            Thread.sleep(10);
        }
    }

    private static ToolCall call(String id, String name, Map<String, ?> args) {
        return new ToolCall(id, name, JSON.toJSONString(args));
    }

    @SuppressWarnings("unused")
    private static List<String> names(List<ToolDefinition> definitions) {
        return definitions.stream().map(ToolDefinition::name).toList();
    }
}
