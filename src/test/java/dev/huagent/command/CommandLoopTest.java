package dev.huagent.command;

import dev.huagent.app.ScheduleMain;
import dev.huagent.app.TeamMain;
import dev.huagent.compact.ContextCompactor;
import dev.huagent.compact.TranscriptStore;
import dev.huagent.config.AgentConfig;
import dev.huagent.goal.GoalController;
import dev.huagent.goal.GoalEvaluator;
import dev.huagent.goal.GoalStarter;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.mcp.McpManager;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.permission.AutoApprove;
import dev.huagent.skill.SkillCatalog;
import dev.huagent.todo.TodoItem;
import dev.huagent.todo.TodoList;
import dev.huagent.todo.TodoStatus;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.workflow.WorkflowStarter;
import dev.huagent.workflow.WorkflowStore;
import dev.huagent.workflow.WorkflowTools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 通过 TeamMain 组装的完整会话：斜杠命令的本地执行、提示词命令与对话历史的替换。 */
@Timeout(60)
class CommandLoopTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String JUDGE_NOT_OK = "{\"ok\":false,\"reason\":\"还没有验证\",\"impossible\":false}";
    private static final String JUDGE_OK = "{\"ok\":true,\"reason\":\"已验证\",\"impossible\":false}";

    @TempDir
    Path root;
    private Path work;
    private Path project;
    private final List<List<ChatMessage>> mainRequests = new CopyOnWriteArrayList<>();
    private final List<List<String>> toolNames = new CopyOnWriteArrayList<>();
    private final List<List<ChatMessage>> summaryRequests = new CopyOnWriteArrayList<>();
    private final List<List<ChatMessage>> judgeRequests = new CopyOnWriteArrayList<>();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final List<List<ChatMessage>> stops = new CopyOnWriteArrayList<>();
    private final TodoList todos = new TodoList();
    /** 判断器的回复；默认判定已完成。 */
    private String judgeReply = JUDGE_OK;

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(root.resolve("work"));
        project = Files.createDirectories(root.resolve("project"));
    }

    @Test
    void localCommandsNeitherRequestModelNorTriggerHooks() throws Exception {
        String output = run("/help\n/status\n/tools\n/skills\n/mcp\n/hooks\n/memory\n/todos\n/tasks\n/bg\n"
                + "/schedules\n/workflows\n/context\n/export\n/goal\n/clear\n/team\nexit\n", 6, false);
        assertTrue(mainRequests.isEmpty(), "本地命令不请求模型");
        assertTrue(prompts.isEmpty(), "本地命令不触发 UserPromptSubmit");
        assertTrue(stops.isEmpty());
        assertTrue(output.contains("输入 /help 查看斜杠命令。"), output);
        for (String expected : List.of("命令> 本地命令只在终端执行", "\n  /clear — ", "\n  * /goal [完成条件 | clear] — ",
                "状态> 模型 fake，目标判断器 fake（每轮最多自动续跑 8 次），接口 OpenAI 格式自定义接口 http://localhost，"
                        + "api-key 已配置（不显示）",
                "团队模式未开启；MCP server 0 个；工作流已启用",
                "工具> 共 32 个：\n  shell — ", "技能> 没有技能（目录 ",
                "MCP> 本会话没有已连接的 MCP server", "Hook> 主 Agent 的回调按执行顺序共 2 个：",
                "  1. 内置日志 LoggingHook（四个事件）\n  2. Java 回调\n",
                "记忆> .memory 中有 0 条", "清单> 任务清单为空。", "任务> .tasks 中有 0 个。",
                "后台> 本次会话没有后台任务。", "计划> 没有定时计划。", "工作流> 没有工作流。",
                "上下文> 对话 0 条消息（user 0、assistant 0、tool 0），约 0 字符",
                "导出> 当前对话为空，未写入。", "目标> 当前没有活跃目标", "清空> 当前对话为空，无需清空。",
                "命令> 未知命令 /team")) {
            assertTrue(output.contains(expected), expected + "\n---\n" + output);
        }
        assertFalse(output.contains("unused"), "api-key 不出现在输出中");
    }

    @Test
    void modelCommandOnCustomClientStaysLocalAndPreservesConversation() throws Exception {
        String output = run("第一轮\n/model\n/model another\n第二轮\nexit\n", 6, false);
        assertEquals(List.of("第一轮", "第二轮"), prompts);
        assertEquals(List.of("system", "user", "assistant", "user"),
                mainRequests.get(1).stream().map(ChatMessage::role).toList());
        assertEquals("第一轮", mainRequests.get(1).get(1).content());
        assertTrue(output.contains("模型> 当前模型 fake"), output);
        assertTrue(output.contains("当前客户端不支持会话内切换模型"), output);
        assertFalse(output.contains("unused"), output);
    }

    @Test
    void noNewToolsAndSystemPromptIdenticalToS17() throws Exception {
        run("你好\nexit\n", 6, false);
        assertEquals(32, toolNames.get(0).size(), "本课不新增工具");
        assertEquals(ScheduleMain.systemPrompt(SkillCatalog.empty(), List.of()) + "\n\n" + WorkflowTools.RULE
                + "\n\n" + GoalController.RULE, mainRequests.get(0).get(0).content());
    }

    @Test
    void previousLessonEntryTreatsSlashTextAsPrompt() throws Exception {
        // commands == null（上一课入口 app.GoalMain 的路径）：/help 是普通提示词，横幅与 S17 相同。
        StringWriter buffer = new StringWriter();
        TeamMain.Session session = TeamMain.createSession(config(6, false), client(), reader("/help\nexit\n"),
                new PrintWriter(buffer, true), project, List.of(recorder()), new HookTrustStore(root.resolve("trust")),
                todos, ZoneOffset.UTC, Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofSeconds(5),
                McpManager.Starter.none(), null, WorkflowStarter.of(work.resolve(WorkflowStore.DIRECTORY_NAME)),
                GoalStarter.of(client(), 8, Clock.fixed(NOW, ZoneOffset.UTC)));
        session.run();
        assertEquals("/help", last(mainRequests.get(0)).content());
        assertFalse(buffer.toString().contains("命令>"), buffer.toString());
        assertFalse(buffer.toString().contains("/help 查看斜杠命令"), buffer.toString());
    }

    @Test
    void unknownCommandIsNotSentButPathLikeTextIs() throws Exception {
        String output = run("/skill\n/status 详细\n/usr/bin/env 是什么\nexit\n", 6, false);
        assertTrue(output.contains("命令> 未知命令 /skill，未发送给模型。你是不是想输入 /skills？"), output);
        assertTrue(output.contains("命令> /status 不接受参数，未执行。用法：/status"), output);
        assertEquals(1, mainRequests.size());
        assertEquals("/usr/bin/env 是什么", last(mainRequests.get(0)).content());
        assertEquals(List.of("/usr/bin/env 是什么"), prompts);
    }

    @Test
    void exitAndQuitCommandsEndSession() throws Exception {
        String output = run("/exit\n你好\n", 6, false);
        assertTrue(output.contains("对话已结束。"), output);
        assertTrue(mainRequests.isEmpty());
        mainRequests.clear();
        run("/quit\n你好\n", 6, false);
        assertTrue(mainRequests.isEmpty());
    }

    @Test
    void clearSavesTranscriptAndClearsHistoryTodosAndGoal() throws Exception {
        judgeReply = JUDGE_NOT_OK;
        todos.replace(List.of(new TodoItem("写测试", TodoStatus.IN_PROGRESS)));
        // 续跑上限 1：一次续跑后交还用户，目标保持活跃。
        String output = run("/goal 完成统计\n/clear\n/goal\n/todos\n第二轮\nexit\n", 6, false, 1);
        assertEquals(2, judgeRequests.size(), "第一轮判断两次后交还；清空后没有目标，第二轮不再判断");
        assertTrue(output.contains("清空> 已清空 4 条消息，完整记录 .transcripts/transcript-"), output);
        assertTrue(output.contains("，任务清单已清空，目标已清除。后台命令、队友、定时计划、记忆与任务文件不受影响"), output);
        assertTrue(output.contains("目标> 当前没有活跃目标"), output);
        assertTrue(output.contains("清单> 任务清单为空。"), output);
        assertTrue(todos.items().isEmpty());

        List<ChatMessage> afterClear = mainRequests.get(mainRequests.size() - 1);
        assertEquals(List.of("system", "user"), afterClear.stream().map(ChatMessage::role).toList());
        assertEquals("第二轮", afterClear.get(1).content());
        assertEquals(mainRequests.get(0).get(0).content(), afterClear.get(0).content(), "系统提示词不变");
        assertEquals(List.of(GoalController.taskPrompt("完成统计"), "第二轮"), prompts, "/clear 不触发 Hook");

        List<Path> transcripts = transcripts();
        assertEquals(1, transcripts.size());
        List<String> lines = Files.readAllLines(transcripts.get(0), StandardCharsets.UTF_8);
        assertEquals(5, lines.size(), "系统提示词、任务、回复、续跑消息、回复");
        assertTrue(lines.get(1).contains("完成统计"), lines.get(1));
    }

    @Test
    void clearKeepsConversationWhenTranscriptCannotBeSaved() throws Exception {
        Files.writeString(work.resolve(TranscriptStore.DIRECTORY_NAME), "不是目录");
        String output = run("第一轮\n/clear\n第二轮\nexit\n", 6, false);
        assertTrue(output.contains("清空> 完整记录写入失败（"), output);
        assertTrue(output.contains("），对话未清空。"), output);
        List<ChatMessage> second = mainRequests.get(1);
        assertEquals(4, second.size(), "历史保留：系统、第一轮、回复、第二轮");
        assertEquals("第一轮", second.get(1).content());
    }

    @Test
    void compactReplacesHistoryWithSummaryUsingFocusWithoutHooks() throws Exception {
        String output = run("/compact\n第一轮\n/compact " + "x".repeat(201) + "\n/compact 保留文件名\n/context\n"
                + "第二轮\nexit\n", 6, false);
        assertTrue(output.contains("压缩> 用户执行 /compact，但没有可总结的历史，跳过"), output);
        assertTrue(output.contains("压缩> 对话未改变。"), output);
        assertTrue(output.contains("压缩> 保留说明最多 200 个字符，当前 201 个，未压缩。"), output);
        assertEquals(1, summaryRequests.size());
        assertTrue(summaryRequests.get(0).get(0).content().endsWith("本次压缩请特别保留：保留文件名"));
        assertTrue(output.contains("压缩> 对话已替换为系统提示词加一条摘要，下一轮从摘要继续。"), output);
        assertTrue(output.contains("上下文> 对话 1 条消息（user 1、assistant 0、tool 0）"), output);

        List<ChatMessage> second = mainRequests.get(1);
        assertEquals(List.of("system", "user", "user"), second.stream().map(ChatMessage::role).toList());
        assertTrue(second.get(1).content().startsWith(ContextCompactor.SUMMARY_MARKER), second.get(1).content());
        assertTrue(second.get(1).content().contains("摘要：\n第一轮的摘要"), second.get(1).content());
        assertEquals("第二轮", second.get(2).content());
        assertEquals(List.of("第一轮", "第二轮"), prompts, "压缩不经过 Hook");
        assertEquals(2, stops.size());
        assertEquals(1, transcripts().size(), "压缩前写入完整记录");
    }

    @Test
    void contextAndExportReflectCurrentConversation() throws Exception {
        String output = run("你好\n/context\n/export\nexit\n", 6, false);
        assertTrue(output.contains("上下文> 对话 2 条消息（user 1、assistant 1、tool 0），约 "), output);
        assertTrue(output.contains("上下文> 系统提示词 " + mainRequests.get(0).get(0).content().length()
                + " 字符，工具 32 个"), output);
        assertTrue(output.contains("导出> 已写入 .transcripts/transcript-"), output);
        assertTrue(output.contains("（3 条消息，含系统提示词）"), output);
        assertEquals(3, Files.readAllLines(transcripts().get(0), StandardCharsets.UTF_8).size());
    }

    @Test
    void goalCommandGoesThroughRegistryWithS17Semantics() throws Exception {
        AgentHook rejectGoal = new AgentHook() {
            @Override
            public String userPromptSubmit(String prompt, List<ChatMessage> history) {
                return prompt.startsWith("[目标]") && prompt.contains("被拒绝") ? "不允许" : null;
            }
        };
        String output = run("/goal 被拒绝的目标\n/goal\n/goal " + "x".repeat(4001) + "\n/goal 统计文件\n/goal\nexit\n",
                6, false, 8, rejectGoal);
        assertTrue(output.contains("钩子拒绝：不允许 提示词未记录，未请求模型。目标未设置。"), output);
        assertTrue(output.contains("目标> 完成条件最多 4000 个字符，当前 4001 个，目标未设置。"), output);
        assertTrue(output.contains("目标> 已设置，开始工作：统计文件"), output);
        assertTrue(output.contains("目标> 已达成"), output);
        assertEquals(2, output.split("目标> 当前没有活跃目标", -1).length - 1, "拒绝后与达成后都没有目标");
        assertEquals(1, mainRequests.size());
        assertEquals(GoalController.taskPrompt("统计文件"), last(mainRequests.get(0)).content());
        assertEquals(1, judgeRequests.size());
    }

    @Test
    void skillCommandSubmitsSkillBodyAsTurnThroughUserPromptSubmit() throws Exception {
        skill("review-code", "审查代码", "步骤一：读文件");
        skill("help", "与内置命令同名", "不会被调用");
        skill("2fa", "数字开头", "只能由模型加载");
        String output = run("/skills\n/help\n/review-code src/A.java\nexit\n", 6, false);
        assertTrue(output.contains("命令> /help 与已有命令重名，/help 未注册。"), output);
        assertTrue(output.contains("命令> 技能 2fa 的名称不以小写字母开头，不能用斜杠命令调用"), output);
        assertTrue(output.contains("技能> 3 个"), output);
        assertTrue(output.contains("\n  review-code — 审查代码"), output);
        assertTrue(output.contains("\n  * /review-code [补充说明] — 技能：审查代码"), output);
        assertTrue(output.contains("技能> 已按 review-code 的说明提交本轮任务。"), output);

        assertEquals(1, prompts.size());
        String prompt = prompts.get(0);
        assertTrue(prompt.startsWith(SkillCommands.MARKER + " 用户输入 /review-code"), prompt);
        assertTrue(prompt.contains("<skill name=\"review-code\">\n步骤一：读文件"), prompt);
        assertTrue(prompt.endsWith("用户补充说明：src/A.java"), prompt);
        assertEquals(prompt, last(mainRequests.get(0)).content(), "经 Hook 放行后记录为本轮用户消息");
        assertEquals(1, stops.size());
    }

    @Test
    void commandFailureIsReportedAndLoopContinues() throws Exception {
        CommandStarter broken = (sources, warnings) -> new CommandRegistry(List.of(SlashCommand.local("broken", "坏",
                (argument, context) -> {
                    throw new IOException("磁盘错误");
                })), warnings);
        String output = runWith(broken, "/broken\n你好\nexit\n", 6, false, 8);
        assertTrue(output.contains("命令> 执行失败（IOException：磁盘错误）"), output);
        assertEquals(1, mainRequests.size());
    }

    @Test
    void teamModeLeadHasTeamCommandAndWakeHook() throws Exception {
        String output = run("/team\n/status\n/hooks\nexit\n", 6, true);
        assertTrue(output.contains("团队> 当前没有队友。"), output);
        assertTrue(output.contains("团队模式已开启（0 个队友）"), output);
        assertTrue(output.contains("团队 PostToolUse：任务图变化时唤醒空闲队友"), output);
        assertTrue(mainRequests.isEmpty());
    }

    @Test
    void autoCommandOnlyWhenHuPassesSwitchAndStatusShowsIt() throws Exception {
        AutoApprove autoApprove = new AutoApprove(false);
        String output = runWith(CommandStarter.builtin(null, autoApprove), "/status\n/auto\n/status\nexit\n", 6, false, 8);
        assertTrue(output.contains("状态> 自动审批模式未开启"), output);
        assertTrue(output.contains("审批> 已开启"), output);
        assertTrue(output.contains("状态> 自动审批模式已开启"), output);
        assertTrue(autoApprove.enabled());
        assertTrue(mainRequests.isEmpty(), "命令不请求模型");

        // 教程入口没有开关：/auto 是未知命令，/status 不提自动审批。
        String tutorial = run("/auto\n/status\nexit\n", 6, false);
        assertTrue(tutorial.contains("未知命令 /auto"), tutorial);
        assertFalse(tutorial.contains("自动审批模式"), tutorial);
    }

    // ---- 脚本与组装辅助 ----

    /** 主循环回复“收到”，总结请求回复固定摘要，判断器按 judgeReply 回复。 */
    private ModelClient client() {
        return new ModelClient() {
            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                String system = messages.get(0).content();
                if (tools.isEmpty() && GoalEvaluator.SYSTEM_PROMPT.equals(system)) {
                    judgeRequests.add(messages);
                    return new ChatMessage("assistant", judgeReply);
                }
                if (tools.isEmpty() && system.startsWith(ContextCompactor.SUMMARIZER_PROMPT)) {
                    summaryRequests.add(messages);
                    return new ChatMessage("assistant", "第一轮的摘要");
                }
                mainRequests.add(messages);
                toolNames.add(tools.stream().map(ToolDefinition::name).toList());
                return new ChatMessage("assistant", "收到");
            }
        };
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

    private String run(String input, int maxModelCalls, boolean team) throws IOException {
        return run(input, maxModelCalls, team, 8);
    }

    private String run(String input, int maxModelCalls, boolean team, int blockCap, AgentHook... extra)
            throws IOException {
        return runWith(CommandStarter.builtin(), input, maxModelCalls, team, blockCap, extra);
    }

    private String runWith(CommandStarter commands, String input, int maxModelCalls, boolean team, int blockCap,
                           AgentHook... extra) throws IOException {
        StringWriter buffer = new StringWriter();
        List<AgentHook> hooks = new ArrayList<>(List.of(recorder()));
        hooks.addAll(List.of(extra));
        ModelClient client = client();
        TeamMain.Session session = TeamMain.createSession(config(maxModelCalls, team), client, reader(input),
                new PrintWriter(buffer, true), project, hooks, new HookTrustStore(root.resolve("trust")), todos,
                ZoneOffset.UTC, Clock.fixed(NOW, ZoneOffset.UTC), false, Duration.ofSeconds(5),
                McpManager.Starter.none(), null, WorkflowStarter.of(work.resolve(WorkflowStore.DIRECTORY_NAME)),
                GoalStarter.of(client, blockCap, Clock.fixed(NOW, ZoneOffset.UTC)), commands);
        session.run();
        return buffer.toString();
    }

    private AgentConfig config(int maxModelCalls, boolean team) {
        return new AgentConfig("http://localhost", "unused", "fake", work, maxModelCalls,
                Duration.ofSeconds(10), 12000, 50000, 3, Duration.ofSeconds(30)).withTeamEnabled(team);
    }

    private void skill(String name, String description, String body) throws IOException {
        Path directory = Files.createDirectories(project.resolve(SkillCatalog.DEFAULT_PATH).resolve(name));
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: " + description
                + "\n---\n\n" + body + "\n", StandardCharsets.UTF_8);
    }

    private List<Path> transcripts() throws IOException {
        try (Stream<Path> files = Files.list(work.resolve(TranscriptStore.DIRECTORY_NAME))) {
            return files.toList();
        }
    }

    private static BufferedReader reader(String input) {
        return new BufferedReader(new StringReader(input));
    }

    private static ChatMessage last(List<ChatMessage> messages) {
        return messages.get(messages.size() - 1);
    }
}
