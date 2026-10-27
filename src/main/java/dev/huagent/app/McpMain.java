package dev.huagent.app;

import dev.huagent.app.TeamMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.config.ProjectDirectory;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.HookConfig;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.mcp.McpConfig;
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
import java.util.Map;

/**
 * S14 入口（上一课）：在 S13 全部能力之上接入 MCP（Model Context Protocol）server。
 * 启动时读取本子项目的 .agents/mcp.json，经用户单独确认后以子进程启动每个 stdio server，
 * 握手、列出工具并包装成 mcp__&lt;server&gt;__&lt;tool&gt;，与内置工具一样经过 Hook 和权限检查。
 * MCP 工具只给主 Agent（团队模式下为 Lead）；子代理与队友没有。
 */
public final class McpMain {
    private McpMain() {
    }

    public static TeamMain.Session createSession(AgentConfig config, ModelClient client, BufferedReader terminal,
                                                 PrintWriter output) throws IOException {
        Path project = ProjectDirectory.resolve();
        return createSession(config, client, terminal, output, project, List.of(), HookTrustStore.userStore(),
                McpTrustStore.userStore(), System.getenv(), new TodoList(), ZoneId.systemDefault(),
                Clock.systemDefaultZone(), true, TeamManager.DEFAULT_IDLE_SCAN);
    }

    /** 显式目录、两个信任存储、环境变量、时钟与空闲扫描间隔，用于可重复的测试。 */
    public static TeamMain.Session createSession(AgentConfig config, ModelClient client, BufferedReader terminal,
                                                 PrintWriter output, Path projectDirectory,
                                                 List<? extends AgentHook> agentCallbacks, HookTrustStore hookTrust,
                                                 McpTrustStore mcpTrust, Map<String, String> environment,
                                                 TodoList todos, ZoneId zone, Clock clock, boolean startScheduler,
                                                 Duration idleScan) throws IOException {
        return TeamMain.createSession(config, client, terminal, output, projectDirectory, agentCallbacks, hookTrust,
                todos, zone, clock, startScheduler, idleScan,
                McpManager.Starter.of(projectDirectory, mcpTrust, environment));
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
            TeamMain.Session session = createSession(config, client, input, output);
            List<String> servers = session.mcp().servers();
            output.println("S14 MCP Plugin：从 " + projectDirectory.resolve(McpConfig.DEFAULT_PATH)
                    + " 接入外部工具，已连接 " + servers.size() + " 个 server" + (servers.isEmpty() ? "" : "："
                    + String.join("、", servers)) + "，" + session.mcp().tools().size()
                    + " 个 MCP 工具（只给主 Agent，未放行的逐次确认）。");
            output.println("保留 S03—S13 全部能力，团队模式" + (session.teamEnabled() ? "已开启" : "未开启（team.enabled=false）")
                    + "。Hook 配置> " + projectDirectory.resolve(HookConfig.DEFAULT_PATH));
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
