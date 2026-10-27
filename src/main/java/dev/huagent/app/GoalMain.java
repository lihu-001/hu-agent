package dev.huagent.app;

import dev.huagent.config.AgentConfig;
import dev.huagent.config.ProjectDirectory;
import dev.huagent.goal.GoalStarter;
import dev.huagent.harness.AgentConsole;
import dev.huagent.harness.EventHub;
import dev.huagent.harness.HarnessOptions;
import dev.huagent.harness.ModelRetryPolicy;
import dev.huagent.hook.HookConfig;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.mcp.McpManager;
import dev.huagent.mcp.McpTrustStore;
import dev.huagent.model.ModelClient;
import dev.huagent.model.OpenAiClient;
import dev.huagent.team.TeamManager;
import dev.huagent.todo.TodoList;
import dev.huagent.workflow.WorkflowStarter;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/**
 * S17 入口（上一课）：在 S16 全部能力之上增加 Goal Loop。
 * 用户用 /goal 设置完成条件后，主 Agent 每次不再调用工具时先由一个没有工具的独立判断器阅读对话记录；
 * 未满足就把理由作为新的用户消息自动续跑，满足或无法完成时清除目标。模型停下只代表这一轮想停，
 * 目标是否完成由判断器决定。不接入斜杠命令（CommandStarter 为 null），行为与 S17 默认入口完全相同。
 */
public final class GoalMain {
    private GoalMain() {
    }

    public static void main(String[] args) {
        Console console = System.console();
        BufferedReader input = new BufferedReader(console == null
                ? new InputStreamReader(System.in, StandardCharsets.UTF_8) : console.reader());
        PrintWriter output = console == null
                ? new PrintWriter(System.out, true, StandardCharsets.UTF_8) : console.writer();
        try {
            AgentConfig config = AgentConfig.load(Path.of("config.properties"));
            ModelClient client = new OpenAiClient(config.baseUrl(), config.apiKey(),
                    config.model(), Duration.ofSeconds(60));
            // 判断器可以单独配置一个更便宜的模型；未配置时与主 Agent 共用客户端。
            ModelClient evaluator = config.goalEvaluatorModel() == null ? client
                    : new OpenAiClient(config.baseUrl(), config.apiKey(), config.goalEvaluatorModel(),
                    Duration.ofSeconds(60));
            Path projectDirectory = ProjectDirectory.resolve();
            AgentConsole agentConsole = new AgentConsole(input, output);
            EventHub events = new EventHub();
            // 事件到达（定时入队、后台完成唤醒）时叫醒空闲的循环。
            events.addListener(agentConsole::notifyEvents);
            HarnessOptions options = new HarnessOptions(events,
                    new ModelRetryPolicy(ModelRetryPolicy.DEFAULT_MAX_RETRIES), agentConsole.prompts());
            WorkflowStarter workflows = WorkflowStarter.of(config);
            GoalStarter goals = GoalStarter.of(evaluator, config.goalBlockCap());
            TeamMain.Session session = TeamMain.createSession(config, client, agentConsole.answers(), output,
                    projectDirectory, List.of(), HookTrustStore.userStore(), new TodoList(),
                    ZoneId.systemDefault(), Clock.systemDefaultZone(), true, TeamManager.DEFAULT_IDLE_SCAN,
                    McpManager.Starter.of(projectDirectory, McpTrustStore.userStore(), System.getenv()),
                    options, workflows, goals);
            List<String> servers = session.mcp().servers();
            output.println("S17 Goal Loop：S01—S16 全部机制加会话目标（/goal <完成条件> 设置并开始，/goal 查看，"
                    + "/goal clear 清除），判断器模型 " + config.evaluatorModel() + "，每轮最多自动续跑 "
                    + config.goalBlockCap() + " 次，MCP 已连接 " + servers.size() + " 个 server"
                    + (servers.isEmpty() ? "" : "：" + String.join("、", servers)) + "。");
            output.println("团队模式" + (session.teamEnabled() ? "已开启" : "未开启（team.enabled=false）")
                    + "；Hook 配置> " + projectDirectory.resolve(HookConfig.DEFAULT_PATH));
            session.run();
        } catch (IllegalArgumentException e) {
            output.println("配置错误：" + e.getMessage());
        } catch (IOException e) {
            output.println("Hook 或 MCP 配置读取、技能、记忆、任务或计划目录扫描、工作目录初始化或终端读取失败（"
                    + e.getClass().getSimpleName() + "），对话结束。");
        } finally {
            output.flush();
        }
    }
}
