package dev.huagent.goal;

import dev.huagent.app.ScheduleMain;
import dev.huagent.app.TeamMain;
import dev.huagent.background.BackgroundTools;
import dev.huagent.config.AgentConfig;
import dev.huagent.harness.EventHub;
import dev.huagent.harness.HarnessOptions;
import dev.huagent.harness.ModelRetryPolicy;
import dev.huagent.harness.TurnEvents;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.mcp.McpManager;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.skill.SkillCatalog;
import dev.huagent.team.TeamTools;
import dev.huagent.todo.TodoList;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.workflow.WorkflowStarter;
import dev.huagent.workflow.WorkflowStore;
import dev.huagent.workflow.WorkflowTools;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** 通过 TeamMain 组装的完整会话：/goal 命令、Goal Gate 续跑与各个出口。 */
class GoalLoopTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path root;
    private Path work;
    private Path project;
    /** 主循环（带工具）的请求，按到达顺序记录。 */
    private final List<List<ChatMessage>> mainRequests = new CopyOnWriteArrayList<>();
    private final List<List<String>> toolNames = new CopyOnWriteArrayList<>();
    /** 判断器请求：只有系统提示词与一条数据消息，不带工具。 */
    private final List<List<ChatMessage>> judgeRequests = new CopyOnWriteArrayList<>();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final List<List<ChatMessage>> stops = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(root.resolve("work"));
        project = Files.createDirectories(root.resolve("project"));
    }

    @Test
    void goalRuleAppendedWithoutNewToolsAndPlainTurnsAreNotJudged() throws Exception {
        String output = run("你好\nexit\n", 6, 8, script(i -> text("你好"), j -> fail("没有目标时不应判断")));
        assertEquals(32, toolNames.get(0).size(), "本课不新增工具：" + toolNames.get(0));
        assertEquals(ScheduleMain.systemPrompt(SkillCatalog.empty(), List.of()) + "\n\n" + WorkflowTools.RULE
                + "\n\n" + GoalController.RULE, mainRequests.get(0).get(0).content());
        assertTrue(judgeRequests.isEmpty());
        assertEquals(1, stops.size(), "没有目标时 Stop 与 S16 相同");
        assertFalse(output.contains("目标>"), output);
    }

    @Test
    void previousLessonEntryHasNoGoalCommand() throws Exception {
        // goals == null（上一课入口 app.WorkflowMain 的路径）：/goal 是普通提示词，提示词与 S16 逐字相同。
        StringWriter buffer = new StringWriter();
        ModelClient client = script(i -> text("收到"), j -> fail("上一课没有判断器"));
        TeamMain.Session session = TeamMain.createSession(config(6), client,
                new BufferedReader(new StringReader("/goal 测试通过\nexit\n")), new PrintWriter(buffer, true),
                project, List.of(recorder()), new HookTrustStore(root.resolve("trust")), new TodoList(),
                ZoneOffset.UTC, Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofSeconds(5),
                McpManager.Starter.none(), null, WorkflowStarter.of(work.resolve(WorkflowStore.DIRECTORY_NAME)));
        session.run();
        assertEquals(ScheduleMain.systemPrompt(SkillCatalog.empty(), List.of()) + "\n\n" + WorkflowTools.RULE,
                mainRequests.get(0).get(0).content());
        assertEquals("/goal 测试通过", last(mainRequests.get(0)).content(), "原样作为用户输入");
        assertFalse(buffer.toString().contains("目标>"), buffer.toString());
    }

    @Test
    void blockedTurnContinuesWithReasonUntilEvaluatorIsSatisfied() throws Exception {
        String output = run("/goal 统计 playground 中的文件数并写明结果\n/goal\nexit\n", 6, 8, script(
                i -> switch (i) {
                    case 1 -> text("我准备开始统计。");
                    case 2 -> ChatMessage.assistant(null, List.of(call("g1", "glob", Map.of("pattern", "*"))));
                    default -> text("glob 返回 0 个文件，结果：0。");
                },
                j -> j == 1 ? notOk("还没有统计结果") : ok("记录中 glob 返回 0 个文件")));
        assertEquals(3, mainRequests.size());
        assertEquals(2, judgeRequests.size());
        assertEquals(List.of(GoalController.taskPrompt("统计 playground 中的文件数并写明结果")), prompts,
                "UserPromptSubmit 只在用户输入时触发一次，内容为目标任务");
        ChatMessage continuation = last(mainRequests.get(1));
        assertEquals("user", continuation.role());
        assertTrue(continuation.content().startsWith(GoalController.CONTINUATION_MARKER), continuation.content());
        assertTrue(continuation.content().contains("判断理由：还没有统计结果"), continuation.content());
        assertTrue(continuation.content().contains("完成条件：统计 playground 中的文件数并写明结果"));
        // 判断器看到的记录包含工具调用和结果，不包含主 Agent 的系统提示词。
        String judged = judgeRequests.get(1).get(1).content();
        assertTrue(judged.contains("\"name\":\"glob\""), judged);
        assertTrue(judged.contains("glob 返回 0 个文件"), judged);
        assertFalse(judged.contains(GoalController.RULE), "系统提示词不交给判断器");
        assertEquals(1, stops.size(), "续跑期间不触发 Stop，最终结束时触发一次");
        List<ChatMessage> turn = stops.get(0);
        assertEquals(GoalController.taskPrompt("统计 playground 中的文件数并写明结果"), turn.get(0).content());
        assertTrue(turn.stream().anyMatch(m -> m.content() != null
                && m.content().startsWith(GoalController.CONTINUATION_MARKER)), "Stop 的本轮记录包含续跑消息");
        assertTrue(output.contains("目标> 已设置，开始工作：统计 playground 中的文件数并写明结果"), output);
        assertTrue(output.contains("目标> 未完成（本轮第 1 次）：还没有统计结果"), output);
        assertTrue(output.contains("目标> 已达成（判断 2 次"), output);
        assertTrue(output.contains("目标> 当前没有活跃目标"), "达成后目标已清除：" + output);
    }

    @Test
    void blockCapHandsControlBackAndKeepsGoal() throws Exception {
        String output = run("/goal 不可能很快完成\n/goal\nexit\n", 10, 2,
                script(i -> text("第 " + i + " 次尝试"), j -> notOk("仍然缺少证据 " + j)));
        assertEquals(3, mainRequests.size(), "首次回复加两次自动续跑");
        assertEquals(3, judgeRequests.size());
        assertTrue(output.contains("本轮已连续自动续跑 2 次仍未完成，交还给你，目标保持活跃：仍然缺少证据 3"), output);
        assertEquals(1, stops.size());
        assertTrue(output.contains("目标> 完成条件：不可能很快完成"), "目标仍然活跃：" + output);
        assertTrue(output.contains("已判断 3 次"), output);
    }

    @Test
    void modelCallBudgetEndsContinuationAndNextInputResumes() throws Exception {
        String output = run("/goal 写出答案\n继续\nexit\n", 2, 8, script(
                i -> text("回复 " + i),
                j -> j < 3 ? notOk("答案还没写出 " + j) : ok("回复 3 写出了答案")));
        assertTrue(output.contains("目标> 已达到本轮模型调用上限，不再续跑，目标保持活跃；输入任意内容继续。"), output);
        assertEquals(3, mainRequests.size(), "第一轮两次请求，第二轮一次");
        assertEquals(3, judgeRequests.size());
        List<ChatMessage> resumed = mainRequests.get(2);
        assertEquals("继续", last(resumed).content());
        assertTrue(resumed.get(resumed.size() - 2).content().equals("回复 2"),
                "上限时的未完成理由不写入历史，下一轮直接接用户输入");
        assertTrue(output.contains("目标> 已达成"), output);
        assertEquals(2, stops.size(), "两轮都以最终回复结束");
    }

    @Test
    void statusClearAndInvalidCommandsDoNotRequestModel() throws Exception {
        String output = run("/goal\n/goal clear\n/goal " + "长".repeat(4001) + "\nexit\n", 6, 8,
                script(i -> fail("命令不请求模型"), j -> fail("命令不请求判断器")));
        assertTrue(output.contains("目标> 当前没有活跃目标。用法：/goal <完成条件>"), output);
        assertTrue(output.contains("目标> 当前没有活跃目标。" + System.lineSeparator()), output);
        assertTrue(output.contains("完成条件最多 4000 个字符"), output);
        assertTrue(mainRequests.isEmpty());
        assertTrue(prompts.isEmpty(), "命令不触发 UserPromptSubmit");
    }

    @Test
    void hookRejectingGoalPromptLeavesGoalUnset() throws Exception {
        AgentHook deny = new AgentHook() {
            @Override
            public String userPromptSubmit(String prompt, List<ChatMessage> history) {
                return prompt.startsWith("[目标]") ? "不允许设置目标" : null;
            }
        };
        String output = run("/goal 测试通过\n/goal\nexit\n", 6, 8,
                script(i -> fail("被拦截"), j -> fail("被拦截")), deny);
        assertTrue(output.contains("钩子拒绝：不允许设置目标 提示词未记录，未请求模型。目标未设置。"), output);
        assertTrue(output.contains("目标> 当前没有活跃目标"), output);
        assertFalse(output.contains("已设置"), output);
    }

    @Test
    void evaluatorFailureStopsAutoContinuationButKeepsGoal() throws Exception {
        String output = run("/goal 测试通过\n/goal\nexit\n", 6, 8,
                script(i -> text("我觉得好了"), j -> text("应该是完成了吧")));
        assertEquals(1, mainRequests.size(), "判断失败不续跑");
        assertTrue(output.contains("目标> 判断器失败（IOException：判断器回复不是合法 JSON）"), output);
        assertEquals(1, stops.size(), "本轮以最终回复结束，照常触发 Stop");
        assertTrue(output.contains("目标> 完成条件：测试通过"), "目标保留：" + output);
    }

    @Test
    void runningBackgroundTaskDefersJudgement() throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String command = windows ? "Start-Sleep -Seconds 20" : "sleep 20";
        String output = run("/goal 构建结束并报告结果\ny\nexit\n", 6, 8, script(
                i -> i == 1 ? ChatMessage.assistant(null, List.of(call("b1", BackgroundTools.RUN,
                        Map.of("command", command)))) : text("已在后台构建，完成后再看。"),
                j -> fail("后台仍在运行时不判断")));
        assertTrue(output.contains("目标> 后台任务 bg_1 仍在运行，结果交回对话后再判断；目标保持活跃。"), output);
        assertTrue(judgeRequests.isEmpty());
        assertEquals(2, mainRequests.size());
        assertEquals(1, stops.size(), "暂缓判断时本轮正常结束");
    }

    @Test
    void eventTurnDoesNotAutoContinue() throws Exception {
        EventHub events = new EventHub();
        // 第一轮判断器失败，留下活跃目标；判断时发布一条定时事件，第二行哨兵触发事件轮。
        ModelClient client = script(
                i -> text("回复 " + i),
                j -> {
                    if (j == 1) {
                        events.publish("[schedule_1] 夜间构建 completed");
                        return text("无法判断");
                    }
                    return notOk("构建结果里没有测试统计");
                });
        HarnessOptions options = new HarnessOptions(events, new ModelRetryPolicy(0),
                new BufferedReader(new StringReader("/goal 夜间构建通过\n" + TurnEvents.WAKE_LINE + "\nexit\n")));
        String output = runWith(options, "", 6, 8, client);
        assertEquals(2, mainRequests.size(), "事件轮判断未完成也不续跑");
        assertEquals(2, judgeRequests.size());
        assertTrue(last(mainRequests.get(1)).content().contains("夜间构建 completed"));
        assertTrue(output.contains("目标> 未完成（本轮第 1 次）：构建结果里没有测试统计"), output);
        assertTrue(output.contains("目标> 程序事件轮不自动续跑，目标保持活跃；输入任意内容继续。"), output);
        assertEquals(2, stops.size());
    }

    @Test
    void teamModeDefersWhileTeammateWorksThenJudgesTeamResult() throws Exception {
        CountDownLatch teammateStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> achieved = new CompletableFuture<>();
        List<String> teammatePrompts = new CopyOnWriteArrayList<>();
        StringWriter buffer = new StringWriter();
        ModelClient client = new ModelClient() {
            int lead;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools)
                    throws InterruptedException {
                if (messages.get(0).content().contains("你的名字是 alice")) {
                    teammatePrompts.add(messages.get(0).content());
                    teammateStarted.countDown();
                    assertTrue(release.await(15, TimeUnit.SECONDS));
                    return text("调研完成：共 3 个模块");
                }
                if (isJudge(messages, tools)) {
                    judgeRequests.add(messages);
                    achieved.complete(null);
                    return ok("team-events 中有 alice 的调研结果");
                }
                mainRequests.add(messages);
                ChatMessage latest = last(messages);
                if ("user".equals(latest.role()) && latest.content().startsWith("<team-events>")) {
                    return text("汇总：alice 调研完成，共 3 个模块。");
                }
                return switch (++lead) {
                    case 1 -> ChatMessage.assistant(null, List.of(call("s1", TeamTools.SPAWN,
                            Map.of("name", "alice", "role", "调研", "prompt", "统计模块"))));
                    default -> {
                        // 等队友真正开始工作，Lead 的最终回复一定落在队友 WORKING 期间。
                        assertTrue(teammateStarted.await(15, TimeUnit.SECONDS));
                        yield text("已安排 alice，等待结果。");
                    }
                };
            }
        };
        PipedWriter keyboard = new PipedWriter();
        TeamMain.Session session = TeamMain.createSession(config(6).withTeamEnabled(true), client,
                new BufferedReader(new PipedReader(keyboard)), new PrintWriter(buffer, true), project,
                List.of(), new HookTrustStore(root.resolve("trust")), new TodoList(), ZoneOffset.UTC,
                Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofSeconds(5), McpManager.Starter.none(), null,
                WorkflowStarter.of(work.resolve(WorkflowStore.DIRECTORY_NAME)),
                GoalStarter.of(client, 8, Clock.fixed(NOW, ZoneOffset.UTC)));
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            try {
                session.run();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        type(keyboard, "/goal 让 alice 统计模块数并汇报");
        awaitText(buffer, "仅允许本次调用？");
        type(keyboard, "y");
        awaitText(buffer, "目标> 队友 alice 仍在工作，结果交回对话后再判断");
        assertTrue(judgeRequests.isEmpty(), "队友工作期间不判断");
        release.countDown();
        achieved.get(15, TimeUnit.SECONDS);
        awaitText(buffer, "目标> 已达成");
        type(keyboard, "exit");
        running.get(15, TimeUnit.SECONDS);
        assertTrue(mainRequests.get(0).get(0).content().endsWith(WorkflowTools.RULE + "\n\n" + GoalController.RULE),
                "Lead 的系统提示词附目标规则");
        assertFalse(teammatePrompts.isEmpty());
        assertTrue(teammatePrompts.stream().noneMatch(p -> p.contains(GoalController.RULE)), "队友没有目标");
        assertTrue(judgeRequests.get(0).get(1).content().contains("调研完成：共 3 个模块"),
                "队友结果作为对话记录交给判断器");
    }

    // ---- 脚本与组装辅助 ----

    @FunctionalInterface
    private interface Step {
        ChatMessage reply(int index) throws Exception;
    }

    /** main 处理主循环第 i 次请求，judge 处理判断器第 j 次请求（都从 1 开始）。 */
    private ModelClient script(Step main, Step judge) {
        return new ModelClient() {
            int mainCount;
            int judgeCount;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws IOException {
                try {
                    if (isJudge(messages, tools)) {
                        judgeRequests.add(messages);
                        return judge.reply(++judgeCount);
                    }
                    mainRequests.add(messages);
                    toolNames.add(tools.stream().map(ToolDefinition::name).toList());
                    return main.reply(++mainCount);
                } catch (IOException | RuntimeException | Error e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static boolean isJudge(List<ChatMessage> messages, List<ToolDefinition> tools) {
        return tools.isEmpty() && GoalEvaluator.SYSTEM_PROMPT.equals(messages.get(0).content());
    }

    private AgentHook recorder() {
        return new AgentHook() {
            @Override
            public String userPromptSubmit(String prompt, List<ChatMessage> history) {
                prompts.add(prompt);
                return null;
            }

            @Override
            public void stop(List<ChatMessage> turn) {
                stops.add(List.copyOf(turn));
            }
        };
    }

    private String run(String input, int maxModelCalls, int blockCap, ModelClient client, AgentHook... extra)
            throws IOException {
        return runWith(null, input, maxModelCalls, blockCap, client, extra);
    }

    private String runWith(HarnessOptions options, String input, int maxModelCalls, int blockCap,
                           ModelClient client, AgentHook... extra) throws IOException {
        StringWriter buffer = new StringWriter();
        List<AgentHook> hooks = new ArrayList<>(List.of(recorder()));
        hooks.addAll(List.of(extra));
        TeamMain.Session session = TeamMain.createSession(config(maxModelCalls), client,
                new BufferedReader(new StringReader(input)), new PrintWriter(buffer, true), project, hooks,
                new HookTrustStore(root.resolve("trust")), new TodoList(), ZoneOffset.UTC,
                Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofSeconds(5), McpManager.Starter.none(), options,
                WorkflowStarter.of(work.resolve(WorkflowStore.DIRECTORY_NAME)),
                GoalStarter.of(client, blockCap, Clock.fixed(NOW, ZoneOffset.UTC)));
        session.run();
        return buffer.toString();
    }

    private AgentConfig config(int maxModelCalls) {
        return new AgentConfig("http://localhost", "unused", "fake", work, maxModelCalls,
                Duration.ofSeconds(10), 12000, 50000, 3, Duration.ofSeconds(30)).withTeamEnabled(false);
    }

    private static ChatMessage text(String content) {
        return new ChatMessage("assistant", content);
    }

    private static ChatMessage ok(String reason) {
        return text("{\"ok\":true,\"reason\":\"" + reason + "\",\"impossible\":false}");
    }

    private static ChatMessage notOk(String reason) {
        return text("{\"ok\":false,\"reason\":\"" + reason + "\",\"impossible\":false}");
    }

    private static ToolCall call(String id, String name, Map<String, ?> args) {
        return new ToolCall(id, name, JSON.toJSONString(args));
    }

    private static ChatMessage last(List<ChatMessage> messages) {
        return messages.get(messages.size() - 1);
    }

    private static void type(PipedWriter keyboard, String line) throws IOException {
        keyboard.write(line + "\n");
        keyboard.flush();
    }

    private static void awaitText(StringWriter buffer, String expected)
            throws InterruptedException, TimeoutException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (buffer.toString().contains(expected)) {
                return;
            }
            Thread.sleep(20);
        }
        fail("等待输出超时：" + expected + "\n" + buffer);
    }
}
