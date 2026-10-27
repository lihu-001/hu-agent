package dev.huagent.app;

import dev.huagent.config.AgentConfig;
import dev.huagent.config.ProjectDirectory;
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
 * S16 入口（上一课）：在 S15 全部能力之上增加 Workflow Runtime 持久化工作流。
 * 工作流保存在工作目录 .workflows/，每个工作流是有序的提示词步骤；运行时复用子代理逐步骤执行，
 * 每步完成即落盘检查点，进程退出或步骤失败后再次运行从断点继续。
 * 不接入目标判断（GoalStarter 为 null），行为与 S16 默认入口完全相同。
 */
public final class WorkflowMain {
    private WorkflowMain() {
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
            Path projectDirectory = ProjectDirectory.resolve();
            AgentConsole agentConsole = new AgentConsole(input, output);
            EventHub events = new EventHub();
            // 事件到达（定时入队、后台完成唤醒）时叫醒空闲的循环。
            events.addListener(agentConsole::notifyEvents);
            HarnessOptions options = new HarnessOptions(events,
                    new ModelRetryPolicy(ModelRetryPolicy.DEFAULT_MAX_RETRIES), agentConsole.prompts());
            WorkflowStarter workflows = WorkflowStarter.of(config);
            TeamMain.Session session = TeamMain.createSession(config, client, agentConsole.answers(), output,
                    projectDirectory, List.of(), HookTrustStore.userStore(), new TodoList(),
                    ZoneId.systemDefault(), Clock.systemDefaultZone(), true, TeamManager.DEFAULT_IDLE_SCAN,
                    McpManager.Starter.of(projectDirectory, McpTrustStore.userStore(), System.getenv()),
                    options, workflows);
            List<String> servers = session.mcp().servers();
            output.println("S16 Workflow Runtime：S01—S15 全部机制加持久化工作流"
                    + "（workflow_create 声明、workflow_run 断点续跑，保存在工作目录 .workflows），"
                    + "MCP 已连接 " + servers.size() + " 个 server" + (servers.isEmpty() ? "" : "："
                    + String.join("、", servers)) + "。");
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
