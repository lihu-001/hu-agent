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
 * S15 入口（上一课）：在 S14 全部能力之上做集成 Harness，不新增独立机制。
 * 本课把 S01—S14 的机制整合到同一条请求管线：
 * 事件唤醒——定时计划的非取消终态进入对话、后台完成与定时结束在空闲时唤醒循环（哨兵轮，不能交互确认）；
 * 错误恢复——HTTP 429/503/529 按 Retry-After 或指数退避重试，上下文超长先响应式压缩再重试。
 * 不接入工作流（WorkflowStarter 为 null），行为与 S15 默认入口完全相同。
 */
public final class HarnessMain {
    private HarnessMain() {
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
            TeamMain.Session session = TeamMain.createSession(config, client, agentConsole.answers(), output,
                    projectDirectory, List.of(), HookTrustStore.userStore(), new TodoList(),
                    ZoneId.systemDefault(), Clock.systemDefaultZone(), true, TeamManager.DEFAULT_IDLE_SCAN,
                    McpManager.Starter.of(projectDirectory, McpTrustStore.userStore(), System.getenv()), options);
            List<String> servers = session.mcp().servers();
            output.println("S15 Integrated Harness：S01—S14 全部机制运行在同一条循环上，"
                    + "定时与后台结束会唤醒对话（限流自动退避重试，上下文超长自动压缩），"
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
