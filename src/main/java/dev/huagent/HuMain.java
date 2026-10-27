package dev.huagent;

import dev.huagent.app.TeamMain;
import dev.huagent.command.CommandStarter;
import dev.huagent.config.AgentConfig;
import dev.huagent.config.ConfigSetup;
import dev.huagent.config.HuHome;
import dev.huagent.config.HuVersion;
import dev.huagent.config.ProjectDirectory;
import dev.huagent.goal.GoalStarter;
import dev.huagent.harness.AgentConsole;
import dev.huagent.harness.EventHub;
import dev.huagent.harness.HarnessOptions;
import dev.huagent.harness.ModelRetryPolicy;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * hu 命令入口：在当前目录启动交互会话，装配与 java-agent 教程 S18 的 CommandMain 相同（全部机制加斜杠命令）。
 * 与 S18 的区别只在位置：配置读 HuHome 下的 config.properties（缺失时首次引导填写），
 * 工作目录与项目级 .agents/（Hook、MCP、技能）都是运行 hu 时所在的目录。
 */
public final class HuMain {
    private HuMain() {
    }

    public static void main(String[] args) {
        Console console = System.console();
        PrintWriter output = console == null
                ? new PrintWriter(System.out, true, StandardCharsets.UTF_8) : console.writer();
        if (args.length > 0) {
            int code = options(args, output);
            output.flush();
            if (code != 0) {
                System.exit(code);
            }
            return;
        }
        BufferedReader input = new BufferedReader(console == null
                ? new InputStreamReader(System.in, StandardCharsets.UTF_8) : console.reader());
        boolean failed = false;
        try {
            Path configFile = HuHome.configFile();
            Map<String, String> environment = System.getenv();
            if (Files.notExists(configFile) && !AgentConfig.coveredByEnvironment(environment)
                    && !ConfigSetup.run(input, output, console, configFile)) {
                output.println();
                output.println("hu> 未完成配置，已退出。");
                return;
            }
            run(AgentConfig.load(configFile, environment), input, output);
        } catch (IllegalArgumentException e) {
            failed = true;
            output.println("配置错误：" + e.getMessage());
        } catch (IOException e) {
            failed = true;
            output.println("Hook 或 MCP 配置读取、技能、记忆、任务或计划目录扫描、工作目录初始化或终端读取失败（"
                    + e.getClass().getSimpleName() + "），对话结束。");
        } finally {
            output.flush();
        }
        if (failed) {
            System.exit(1);
        }
    }

    private static void run(AgentConfig config, BufferedReader input, PrintWriter output) throws IOException {
        ModelClient client = new OpenAiClient(config.baseUrl(), config.apiKey(), config.model(), Duration.ofSeconds(60));
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
        TeamMain.Session session = TeamMain.createSession(config, client, agentConsole.answers(), output,
                projectDirectory, List.of(), HookTrustStore.userStore(), new TodoList(),
                ZoneId.systemDefault(), Clock.systemDefaultZone(), true, TeamManager.DEFAULT_IDLE_SCAN,
                McpManager.Starter.of(projectDirectory, McpTrustStore.userStore(), System.getenv()),
                options, WorkflowStarter.of(config), GoalStarter.of(evaluator, config.goalBlockCap()),
                CommandStarter.builtin());
        List<String> servers = session.mcp().servers();
        output.println("hu-agent " + HuVersion.current() + " · 模型 " + config.model() + " · 工作目录 "
                + config.workDirectory().toAbsolutePath().normalize());
        output.println("输入 /help 查看命令，exit 退出。"
                + (servers.isEmpty() ? "" : "MCP 已连接：" + String.join("、", servers) + "。")
                + (session.teamEnabled() ? "团队模式已开启。" : ""));
        session.run();
    }

    /** 返回进程退出码：0 已处理，2 参数错误。 */
    static int options(String[] args, PrintWriter output) {
        String option = args[0];
        if (args.length == 1 && (option.equals("-v") || option.equals("--version"))) {
            output.println("hu-agent " + HuVersion.current());
            return 0;
        }
        if (args.length == 1 && (option.equals("-h") || option.equals("--help"))) {
            output.print(usage());
            return 0;
        }
        output.println("hu: 不支持的参数：" + String.join(" ", args));
        output.print(usage());
        return 2;
    }

    static String usage() {
        return """
                用法：hu [选项]
                  在当前目录启动 hu-agent 交互会话；进入后输入 /help 查看命令，exit 退出。

                选项：
                  -h, --help       显示本帮助
                  -v, --version    显示版本

                配置：%s
                  环境变量 HU_BASE_URL、HU_MODEL、HU_API_KEY 优先于配置文件。
                  项目级 Hook、MCP 与技能读取当前目录的 .agents/。
                """.formatted(HuHome.configFile());
    }
}
