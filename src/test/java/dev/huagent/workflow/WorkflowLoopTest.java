package dev.huagent.workflow;

import dev.huagent.app.ScheduleMain;
import dev.huagent.app.TeamMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.mcp.McpManager;
import dev.huagent.skill.SkillCatalog;
import dev.huagent.todo.TodoList;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.team.TeamTools;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 通过 TeamMain 组装的完整会话：工作流工具只给主 Agent（团队模式为 Lead），执行经过同一权限与 Hook 链。 */
class WorkflowLoopTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:30Z");
    private static final Pattern WORKFLOW_ID = Pattern.compile("workflow_[0-9a-f]{8}");

    @TempDir
    Path root;
    private Path work;
    private Path project;
    /** 主循环与子代理的请求按到达顺序记录。 */
    private final List<List<ChatMessage>> requests = new CopyOnWriteArrayList<>();
    private final List<List<String>> toolNames = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(root.resolve("work"));
        project = Files.createDirectories(root.resolve("project"));
    }

    @Test
    void registersWorkflowToolsAndPromptAddsRule() throws Exception {
        String output = run("x\nexit\n", scriptModel("ok"));
        List<String> names = toolNames.get(0);
        assertEquals(32, names.size(), names.toString());
        assertEquals(WorkflowTools.NAMES, names.subList(27, 32));
        assertEquals(ScheduleMain.systemPrompt(SkillCatalog.empty(), List.of()) + "\n\n" + WorkflowTools.RULE,
                requests.get(0).get(0).content());
        assertTrue(output.contains("工作流> .workflows 中有 0 个工作流，已完成 0 个"), output);
        assertFalse(Files.exists(work.resolve(WorkflowStore.DIRECTORY_NAME)), "未创建工作流时不建目录");
    }

    @Test
    void previousLessonEntryHasNoWorkflowTools() throws Exception {
        // workflows == null（上一课入口 app.HarnessMain 的路径）：工具、提示词、启动输出与 S15 完全相同。
        StringWriter buffer = new StringWriter();
        TeamMain.Session session = TeamMain.createSession(config(), scriptModel("ok"),
                new BufferedReader(new StringReader("x\nexit\n")), new PrintWriter(buffer, true), project,
                List.of(), new HookTrustStore(root.resolve("trust")), new TodoList(), ZoneOffset.UTC,
                Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofSeconds(5));
        session.run();
        assertEquals(27, toolNames.get(0).size(), "只有 S15 的二十七个工具");
        assertTrue(toolNames.get(0).stream().noneMatch(name -> name.startsWith("workflow_")));
        assertEquals(ScheduleMain.systemPrompt(SkillCatalog.empty(), List.of()),
                requests.get(0).get(0).content());
        assertFalse(buffer.toString().contains("工作流>"), buffer.toString());
    }

    @Test
    void createAndRunWorkflowThroughSubagentsWithoutConfirmation() throws Exception {
        WorkflowStore store = store();
        String[] runResult = new String[1];
        int[] subCalls = {0};
        String output = run("跑工作流\nexit\n", new ModelClient() {
            int main;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                recordRequest(messages, tools);
                if (!isMainLoop(tools)) {
                    // 子代理：第二步的说明自动带上第一步的结果。
                    subCalls[0]++;
                    if (subCalls[0] == 2) {
                        String prompt = messages.get(1).content();
                        assertTrue(prompt.startsWith("读取结果写汇总"), prompt);
                        assertTrue(prompt.contains("<前序步骤结果>"), prompt);
                        assertTrue(prompt.contains("步骤 1 的结果：\n步骤 1 的结果"), prompt);
                    }
                    return new ChatMessage("assistant", "步骤 " + subCalls[0] + " 的结果");
                }
                return switch (++main) {
                    case 1 -> ChatMessage.assistant(null, List.of(call("c1", WorkflowTools.CREATE, Map.of(
                            "name", "站点检查", "steps", List.of("统计任务数", "读取结果写汇总")))));
                    case 2 -> {
                        String created = contentOf(messages, "c1");
                        assertTrue(created.contains("已创建 workflow_"), created);
                        yield ChatMessage.assistant(null, List.of(call("r1", WorkflowTools.RUN,
                                Map.of("id", firstId(created)))));
                    }
                    default -> {
                        runResult[0] = contentOf(messages, "r1");
                        yield new ChatMessage("assistant", "工作流完成");
                    }
                };
            }
        });
        assertFalse(output.contains("[y/N]"), "工作流工具免确认：" + output);
        assertTrue(output.contains("工作流> workflow_"), output);
        assertTrue(output.contains("步骤 1/2 开始执行"), output);
        assertTrue(output.contains("步骤 2 完成"), output);
        assertEquals(2, subCalls[0]);
        assertTrue(runResult[0].contains("状态：completed"), runResult[0]);
        assertTrue(runResult[0].contains("[x] 步骤 2 [completed]：步骤 2 的结果"), runResult[0]);
        WorkflowRecord done = store.list(w -> fail(w)).get(0);
        assertEquals(WorkflowStatus.COMPLETED, done.status());
        assertEquals("步骤 1 的结果", done.steps().get(0).result());
        // 子代理只有六个基础工具（五个基础 + load_skill），没有工作流工具。
        int subIndex = indexOfSubagentRequest();
        assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob", "grep", "load_skill"),
                toolNames.get(subIndex));
    }

    @Test
    void failureStopsWorkflowAndNextSessionResumesFromCheckpoint() throws Exception {
        WorkflowStore store = store();
        // 第一次会话：第二步的子代理请求超时，工作流 failed。
        StringWriter first = new StringWriter();
        TeamMain.Session sessionOne = session(first, "建流\nexit\n", new ModelClient() {
            int main;
            int sub;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools)
                    throws IOException {
                recordRequest(messages, tools);
                if (!isMainLoop(tools)) {
                    if (++sub == 2) {
                        throw new HttpTimeoutException("timeout");
                    }
                    return new ChatMessage("assistant", "第一步完成");
                }
                return switch (++main) {
                    case 1 -> createCall("c1");
                    case 2 -> runCall("r1", firstId(contentOf(messages, "c1")));
                    default -> new ChatMessage("assistant", "第一次结束");
                };
            }
        });
        sessionOne.run();
        WorkflowRecord failed = store.list(w -> fail(w)).get(0);
        assertEquals(WorkflowStatus.FAILED, failed.status());
        assertTrue(failed.lastError().contains("步骤 2 失败"), failed.lastError());
        assertEquals(StepStatus.COMPLETED, failed.steps().get(0).status());
        assertEquals(StepStatus.FAILED, failed.steps().get(1).status());

        // 第二次会话：先 workflow_list 拿到 ID，再继续，只有第二步重跑。
        List<String> resumedPrompts = new ArrayList<>();
        StringWriter second = new StringWriter();
        TeamMain.Session sessionTwo = session(second, "继续\nexit\n", new ModelClient() {
            int main;
            int sub;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                recordRequest(messages, tools);
                if (!isMainLoop(tools)) {
                    resumedPrompts.add(messages.get(1).content());
                    assertTrue(messages.get(1).content().startsWith("读取结果写汇总"),
                            "从第二步继续：" + messages.get(1).content());
                    assertTrue(messages.get(1).content().contains("第一步完成"),
                            "前序结果随步骤传递：" + messages.get(1).content());
                    return new ChatMessage("assistant", "第二步好了");
                }
                return switch (++main) {
                    case 1 -> ChatMessage.assistant(null, List.of(call("l1", WorkflowTools.LIST, Map.of())));
                    case 2 -> runCall("r2", firstId(contentOf(messages, "l1")));
                    default -> new ChatMessage("assistant", "第二次结束");
                };
            }
        });
        sessionTwo.run();
        assertTrue(second.toString().contains("工作流> .workflows 中有 1 个工作流，已完成 0 个"), second.toString());
        assertEquals(1, resumedPrompts.size(), "已完成的步骤不重跑");
        WorkflowRecord done = store.list(w -> fail(w)).get(0);
        assertEquals(WorkflowStatus.COMPLETED, done.status());
        assertEquals("第一步完成", done.steps().get(0).result());
        assertEquals("第二步好了", done.steps().get(1).result());
        assertNull(done.lastError());
    }

    @Test
    void interruptDuringRunMarksPaused() throws Exception {
        WorkflowStore store = store();
        StringWriter buffer = new StringWriter();
        TeamMain.Session session = session(buffer, "跑\nexit\n", new ModelClient() {
            int main;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools)
                    throws InterruptedException {
                recordRequest(messages, tools);
                if (!isMainLoop(tools)) {
                    throw new InterruptedException();
                }
                return switch (++main) {
                    case 1 -> createCall("c1");
                    case 2 -> runCall("r1", firstId(contentOf(messages, "c1")));
                    default -> new ChatMessage("assistant", "不会到达");
                };
            }
        });
        session.run();
        assertTrue(buffer.toString().contains("请求已中断"), buffer.toString());
        assertTrue(buffer.toString().contains("再次运行可从第 1 步继续"), buffer.toString());
        WorkflowRecord paused = store.list(w -> fail(w)).get(0);
        assertEquals(WorkflowStatus.PAUSED, paused.status());
        assertEquals(StepStatus.PENDING, paused.steps().get(0).status());
    }

    @Test
    void startupRepairsRunningAndSkipsInvalidFiles() throws Exception {
        WorkflowStore store = store();
        Path dir = Files.createDirectories(store.directory());
        WorkflowRecord stuck = store.create("上次未结束", List.of("一步", "两步"), NOW);
        List<WorkflowStep> steps = List.of(stuck.steps().get(0), stuck.steps().get(1).markRunning(NOW));
        store.save(new WorkflowRecord(stuck.id(), stuck.name(), WorkflowStatus.RUNNING, steps, NOW, NOW, null));
        Files.writeString(dir.resolve("workflow_99999999.json"), "坏文件");

        String[] listed = new String[1];
        String output = run("看看\nexit\n", new ModelClient() {
            int main;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                recordRequest(messages, tools);
                return switch (++main) {
                    case 1 -> ChatMessage.assistant(null, List.of(call("l1", WorkflowTools.LIST, Map.of())));
                    default -> {
                        listed[0] = contentOf(messages, "l1");
                        yield new ChatMessage("assistant", "好");
                    }
                };
            }
        });
        assertTrue(output.contains("已修复为 paused"), output);
        assertTrue(output.contains("工作流> 跳过 workflow_99999999.json"), output);
        assertEquals(WorkflowStatus.PAUSED, store.load(stuck.id()).status());
        assertEquals(StepStatus.PENDING, store.load(stuck.id()).steps().get(1).status());
        assertTrue(listed[0].contains("状态=paused"), listed[0]);
    }

    @Test
    void workflowToolsPassThroughHooksAndCanBeBlocked() throws Exception {
        List<String> events = new ArrayList<>();
        AgentHook hook = new AgentHook() {
            @Override
            public String preToolUse(ToolCall call) {
                events.add("pre:" + call.name());
                return WorkflowTools.CREATE.equals(call.name()) ? "禁止建工作流" : null;
            }

            @Override
            public void postToolUse(ToolCall call, String result, long elapsedNanos) {
                events.add("post:" + call.name());
            }
        };
        String output = run("x\nexit\n", new ModelClient() {
            int main;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                recordRequest(messages, tools);
                return switch (++main) {
                    case 1 -> ChatMessage.assistant(null, List.of(
                            call("c1", WorkflowTools.CREATE, Map.of("name", "被拦截", "steps", List.of("一步"))),
                            call("l1", WorkflowTools.LIST, Map.of())));
                    default -> new ChatMessage("assistant", "好");
                };
            }
        }, hook);
        assertEquals(List.of("pre:workflow_create", "pre:workflow_list", "post:workflow_list"), events,
                "被拦截的 workflow_create 不执行也没有 PostToolUse；放行的 workflow_list 正常触发");
        assertTrue(output.contains("钩子拒绝：禁止建工作流"), output);
        assertFalse(Files.exists(work.resolve(WorkflowStore.DIRECTORY_NAME)));
    }

    @Test
    void teamModeLeadHasWorkflowToolsAndTeammatesDoNot() throws Exception {
        List<String> teammateTools = new CopyOnWriteArrayList<>();
        StringWriter buffer = new StringWriter();
        java.util.concurrent.CompletableFuture<Void> teammateAnswered = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<Void> leadWoken = new java.util.concurrent.CompletableFuture<>();
        ModelClient client = new ModelClient() {
            int main;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                List<String> names = tools.stream().map(ToolDefinition::name).toList();
                if (messages.get(0).content().contains("你的名字是 alice")) {
                    teammateTools.addAll(names);
                    teammateAnswered.complete(null);
                    return new ChatMessage("assistant", "队友调查完成");
                }
                recordRequest(messages, tools);
                ChatMessage latest = messages.get(messages.size() - 1);
                if ("user".equals(latest.role()) && latest.content().startsWith("<team-events>")) {
                    leadWoken.complete(null);
                    return new ChatMessage("assistant", "汇总完成");
                }
                return switch (++main) {
                    case 1 -> ChatMessage.assistant(null, List.of(call("s1", TeamTools.SPAWN,
                            Map.of("name", "alice", "role", "调研", "prompt", "看看"))));
                    default -> new ChatMessage("assistant", "已安排 alice");
                };
            }
        };
        java.io.PipedWriter keyboard = new java.io.PipedWriter();
        TeamMain.Session session = TeamMain.createSession(config().withTeamEnabled(true), client,
                new BufferedReader(new java.io.PipedReader(keyboard)), new PrintWriter(buffer, true), project,
                List.of(), new HookTrustStore(root.resolve("trust")), new TodoList(), ZoneOffset.UTC,
                Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofSeconds(5),
                McpManager.Starter.none(), null,
                WorkflowStarter.of(work.resolve(WorkflowStore.DIRECTORY_NAME)));
        java.util.concurrent.CompletableFuture<Void> running =
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        session.run();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
        type(keyboard, "组队");
        awaitText(buffer, "仅允许本次调用？");
        type(keyboard, "y");
        teammateAnswered.get(15, java.util.concurrent.TimeUnit.SECONDS);
        // 队友的结果唤醒 Lead，工作流工具照常可用（本轮无需再调用）。
        leadWoken.get(15, java.util.concurrent.TimeUnit.SECONDS);
        type(keyboard, "exit");
        running.get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(36, toolNames.get(0).size(), toolNames.get(0).toString());
        assertEquals(WorkflowTools.NAMES, toolNames.get(0).subList(31, 36));
        assertTrue(toolNames.get(0).containsAll(TeamTools.LEAD_NAMES));
        assertEquals(ScheduleMain.systemPrompt(SkillCatalog.empty(), List.of()) + "\n\n" + TeamMain.TEAM_RULE
                        + "\n\n" + WorkflowTools.RULE,
                requests.get(0).get(0).content());
        assertFalse(teammateTools.isEmpty(), "队友已经发起过请求");
        assertTrue(teammateTools.stream().noneMatch(name -> name.startsWith("workflow_")),
                "队友没有工作流工具：" + teammateTools);
        assertTrue(teammateTools.contains("send_message") && teammateTools.contains("task_list"),
                "队友是完整 Agent：" + teammateTools);
    }

    private static void type(java.io.PipedWriter keyboard, String line) throws IOException {
        keyboard.write(line + "\n");
        keyboard.flush();
    }

    private static void awaitText(StringWriter buffer, String expected)
            throws InterruptedException, java.util.concurrent.TimeoutException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (buffer.toString().contains(expected)) {
                return;
            }
            Thread.sleep(20);
        }
        fail("等待输出超时：" + expected + "\n" + buffer);
    }

    // ---- 脚本与组装辅助 ----

    private static ChatMessage createCall(String callId) {
        return ChatMessage.assistant(null, List.of(call(callId, WorkflowTools.CREATE, Map.of(
                "name", "站点检查", "steps", List.of("统计任务数", "读取结果写汇总")))));
    }

    private static ChatMessage runCall(String callId, String id) {
        return ChatMessage.assistant(null, List.of(call(callId, WorkflowTools.RUN, Map.of("id", id))));
    }

    private ModelClient scriptModel(String text) {
        return (messages, tools) -> {
            recordRequest(messages, tools);
            return new ChatMessage("assistant", text);
        };
    }

    private TeamMain.Session session(StringWriter buffer, String input, ModelClient client) throws IOException {
        return TeamMain.createSession(config(), client, new BufferedReader(new StringReader(input)),
                new PrintWriter(buffer, true), project, List.of(),
                new HookTrustStore(root.resolve("trust")), new TodoList(), ZoneOffset.UTC,
                Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofSeconds(5),
                McpManager.Starter.none(), null,
                WorkflowStarter.of(work.resolve(WorkflowStore.DIRECTORY_NAME)));
    }

    private String run(String input, ModelClient client, AgentHook... hooks) throws IOException {
        StringWriter buffer = new StringWriter();
        TeamMain.Session session = TeamMain.createSession(config(), client,
                new BufferedReader(new StringReader(input)), new PrintWriter(buffer, true), project,
                List.of(hooks), new HookTrustStore(root.resolve("trust")), new TodoList(), ZoneOffset.UTC,
                Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofSeconds(5),
                McpManager.Starter.none(), null,
                WorkflowStarter.of(work.resolve(WorkflowStore.DIRECTORY_NAME)));
        session.run();
        return buffer.toString();
    }

    private AgentConfig config() {
        return new AgentConfig("http://localhost", "unused", "fake", work, 6,
                Duration.ofSeconds(10), 12000, 50000, 3, Duration.ofSeconds(30)).withTeamEnabled(false);
    }

    private WorkflowStore store() {
        return new WorkflowStore(work.resolve(WorkflowStore.DIRECTORY_NAME));
    }

    private void recordRequest(List<ChatMessage> messages, List<ToolDefinition> tools) {
        requests.add(messages);
        toolNames.add(tools.stream().map(ToolDefinition::name).toList());
    }

    private boolean isMainLoop(List<ToolDefinition> tools) {
        return tools.stream().anyMatch(tool -> WorkflowTools.CREATE.equals(tool.name()));
    }

    private int indexOfSubagentRequest() {
        for (int i = 0; i < toolNames.size(); i++) {
            if (toolNames.get(i).size() == 7) {
                return i;
            }
        }
        return fail("没有子代理请求");
    }

    private static ToolCall call(String id, String name, Map<String, ?> args) {
        return new ToolCall(id, name, JSON.toJSONString(args));
    }

    private static String contentOf(List<ChatMessage> messages, String callId) {
        return messages.stream().filter(m -> callId.equals(m.toolCallId())).findFirst().orElseThrow().content();
    }

    private static String firstId(String text) {
        Matcher matcher = WORKFLOW_ID.matcher(text);
        assertTrue(matcher.find(), text);
        return matcher.group();
    }
}
