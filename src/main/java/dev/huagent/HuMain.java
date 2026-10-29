package dev.huagent;

import dev.huagent.app.TeamMain;
import dev.huagent.command.CommandRegistry;
import dev.huagent.command.CommandStarter;
import dev.huagent.config.AgentConfig;
import dev.huagent.config.ConfigSetup;
import dev.huagent.config.HuHome;
import dev.huagent.config.HuVersion;
import dev.huagent.config.ModelDefaults;
import dev.huagent.config.ProjectDirectory;
import dev.huagent.context.ProjectContext;
import dev.huagent.goal.GoalStarter;
import dev.huagent.harness.AgentConsole;
import dev.huagent.harness.EventHub;
import dev.huagent.harness.HarnessOptions;
import dev.huagent.harness.ModelRetryPolicy;
import dev.huagent.harness.Transcript;
import dev.huagent.harness.TurnInterrupt;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.mcp.McpManager;
import dev.huagent.mcp.McpTrustStore;
import dev.huagent.permission.ConfirmPrompt;
import dev.huagent.permission.AutoApprove;
import dev.huagent.model.ModelClient;
import dev.huagent.model.ModelClients;
import dev.huagent.prompt.FramedPrompt;
import dev.huagent.prompt.LineEditor;
import dev.huagent.prompt.PromptStyle;
import dev.huagent.prompt.Splash;
import dev.huagent.prompt.TerminalTitle;
import dev.huagent.prompt.TerminalWidth;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/**
 * hu 命令入口：在当前目录启动交互会话，装配与 java-agent 教程 S18 的 CommandMain 相同（全部机制加斜杠命令）。
 * 与 S18 的区别：模型接口读 HuHome 下的 model.properties（缺失时首次引导选择接口类型并填写，支持 OpenAI 与
 * Anthropic 协议），其他设置读同目录的 config.properties；
 * 工作目录与项目级 .agents/（Hook、MCP、技能）都是运行 hu 时所在的目录。
 */
public final class HuMain {
    /**
     * 一次模型请求的超时。请求不是流式的，超时要覆盖模型生成整段回复（含思考和大段编辑内容）的时间，
     * 60 秒经常不够；与 Claude Code 的默认值相同取 10 分钟，等待期间随时可以按 Esc 中断。
     */
    static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private HuMain() {
    }

    public static void main(String[] args) {
        Console console = System.console();
        PrintWriter output = console == null
                ? new PrintWriter(System.out, true, StandardCharsets.UTF_8) : console.writer();
        SessionFlags flags = sessionFlags(args);
        if (flags == null) {
            int code = options(args, output);
            output.flush();
            if (code != 0) {
                System.exit(code);
            }
            return;
        }
        boolean debug = flags.debug();
        // Lead、子代理与队友的权限检查、输入栏和 /auto、/status 共用这一个开关。
        AutoApprove autoApprove = new AutoApprove(flags.autoApprove());
        BufferedReader input = new BufferedReader(console == null
                ? new InputStreamReader(System.in, StandardCharsets.UTF_8) : console.reader());
        LineEditor editor = null;
        boolean failed = false;
        try {
            Path configFile = HuHome.configFile();
            Path modelFile = HuHome.modelFile();
            Map<String, String> environment = System.getenv();
            if (!AgentConfig.modelConfigured(configFile, modelFile, environment)
                    && !ConfigSetup.run(input, output, console, modelFile, configFile)) {
                output.println();
                output.println("hu> 未完成配置，已退出。");
                return;
            }
            AgentConfig config = AgentConfig.load(configFile, modelFile, environment);
            // /model 把选择写回读取模型的那个文件（与 load 的选择相同）。
            ModelDefaults defaults = ModelDefaults.locate(configFile, modelFile, environment);
            // 首次配置引导用 Console 读取（API Key 不回显）；之后由行编辑器接管终端，Console 不再读写。
            AtomicReference<CommandRegistry> registry = new AtomicReference<>();
            editor = console == null ? null : LineEditor.open(() -> {
                CommandRegistry bound = registry.get();
                return bound == null ? List.of() : bound.commands();
            });
            // 终端标题跟着会话状态变化，只在行编辑器接管终端时设置（见 TerminalTitle）。
            TerminalTitle title = editor == null ? null : new TerminalTitle(editor::title, config.workDirectory());
            if (editor != null) {
                output.flush();
                output = editor.output();
                input = title.input(editor.input());
            }
            // 任务运行中按 Esc 或 Ctrl+C 中断（与 Claude Code 相同）：行编辑器在任务期间读按键，交给 ChatLoop 结束本轮。
            TurnInterrupt interrupt = new TurnInterrupt();
            if (editor != null) {
                editor.onInterrupt(interrupt::request);
                interrupt.addListener(editor::taskRunning);
            }
            IntSupplier columns = editor == null ? TerminalWidth.system() : editor::columns;
            PromptStyle prompt = promptStyle(console, config, columns, autoApprove);
            // 补全要用会话装配出的注册表（含技能命令）。
            CommandStarter commands = (sources, warnings) -> {
                CommandRegistry bound = CommandStarter.builtin(defaults, autoApprove).bind(sources, warnings);
                registry.set(bound);
                return bound;
            };
            run(config, input, output, debug, autoApprove, title == null ? prompt : title.prompt(prompt),
                    confirmPrompt(console, debug), transcript(console, debug), splash(console, columns), commands,
                    editor != null, interrupt);
        } catch (IllegalArgumentException e) {
            failed = true;
            output.println("配置错误：" + e.getMessage());
        } catch (IOException e) {
            failed = true;
            output.println("Hook 或 MCP 配置读取、技能、记忆、任务或计划目录扫描、工作目录初始化或终端读取失败（"
                    + e.getClass().getSimpleName() + "），对话结束。");
        } finally {
            output.flush();
            if (editor != null) {
                editor.close();
            }
        }
        if (failed) {
            System.exit(1);
        }
    }

    private static void run(AgentConfig config, BufferedReader input, PrintWriter output, boolean debug,
                            AutoApprove autoApprove, PromptStyle prompt, ConfirmPrompt confirm,
                            Transcript transcript, String splash, CommandStarter commands, boolean completion,
                            TurnInterrupt interrupt)
            throws IOException {
        // 启动页先于会话装配输出：MCP server 启动可能较慢，Hook 与 MCP 的授权确认也排在它下面。
        if (splash != null) {
            output.print(splash);
            output.flush();
        }
        ModelClient client = ModelClients.create(config.modelConfig(), REQUEST_TIMEOUT);
        // 判断器可以单独配置一个更便宜的模型（同一接口）；未配置时与主 Agent 共用客户端。
        ModelClient evaluator = config.goalEvaluatorModel() == null ? client
                : ModelClients.create(config.modelConfig().withModel(config.goalEvaluatorModel()), REQUEST_TIMEOUT);
        Path projectDirectory = ProjectDirectory.resolve();
        AgentConsole agentConsole = new AgentConsole(input, output);
        EventHub events = new EventHub();
        // 事件到达（定时入队、后台完成唤醒）时叫醒空闲的循环。
        events.addListener(agentConsole::notifyEvents);
        // 与 Claude Code 的环境信息和 CLAUDE.md 相同：会话开始时采集一次，模型从第一轮起就知道身处哪个项目。
        String projectContext = ProjectContext.render(config.workDirectory(), HuHome.resolve(), LocalDate.now());
        HarnessOptions options = new HarnessOptions(events,
                new ModelRetryPolicy(ModelRetryPolicy.DEFAULT_MAX_RETRIES), agentConsole.prompts(), debug, prompt,
                confirm, autoApprove, interrupt, projectContext, transcript);
        // 单 Agent 模式的确认在主线程上读这里；中断请求等回答读完再中断主线程（见 TurnInterrupt.answers）。
        BufferedReader answers = interrupt.answers(agentConsole.answers());
        TeamMain.Session session = TeamMain.createSession(config, client, answers, output,
                projectDirectory, List.of(), HookTrustStore.userStore(), new TodoList(),
                ZoneId.systemDefault(), Clock.systemDefaultZone(), true, TeamManager.DEFAULT_IDLE_SCAN,
                McpManager.Starter.of(projectDirectory, McpTrustStore.userStore(), System.getenv()),
                options, WorkflowStarter.of(config), GoalStarter.of(evaluator, config.goalBlockCap()), commands);
        List<String> servers = session.mcp().servers();
        String location = "模型 " + config.model() + "（" + config.modelConfig().provider().label() + "）· 工作目录 "
                + config.workDirectory().toAbsolutePath().normalize();
        output.println(splash == null ? "hu-agent " + HuVersion.current() + " · " + location : location);
        output.println((completion ? "输入 / 列出命令，Tab 或 ↑↓ 选择，运行中按 Esc 中断" : "输入 /help 查看命令")
                + "，exit 退出。"
                + (servers.isEmpty() ? "" : "MCP 已连接：" + String.join("、", servers) + "。")
                + (session.teamEnabled() ? "团队模式已开启。" : "")
                + (debug ? "调试模式已开启。" : ""));
        if (autoApprove.enabled()) {
            output.println("自动审批模式已开启：需要确认的工具调用（Shell、写文件、MCP 工具等）自动批准，"
                    + "硬拒绝规则与工作目录检查仍然生效；/auto off 关闭。");
        }
        session.run();
    }

    /**
     * 交互终端上在输入上方画分隔线标出用户输入，线上显示模型与上下文用量（最大值取 model.properties 的 max-context-tokens）；
     * 输入输出被重定向（没有 Console）时保留 "你> "，日志里不出现分隔线。
     */
    static PromptStyle promptStyle(Console console, AgentConfig config, IntSupplier columns, AutoApprove autoApprove) {
        return console == null ? PromptStyle.plain()
                : FramedPrompt.forTerminal(console.charset(), columns, config.model(), config.maxContextTokens(),
                        autoApprove::enabled);
    }

    /**
     * 权限确认：调试模式保持原来的多行样式；默认压成以【权限确认】开头的一行，
     * 交互终端能解释 ANSI 时（与启动页的判断相同）显示为黄色，输出被重定向时不带颜色。
     */
    static ConfirmPrompt confirmPrompt(Console console, boolean debug) {
        return ConfirmPrompt.of(debug, console != null && Splash.ColorSupport.system() != Splash.ColorSupport.NONE);
    }

    /**
     * 回复与工具调用摘要：交互终端上与 Claude Code 一样各成一段（● 开头、段间空行），能解释 ANSI 时工具带颜色；
     * 调试模式与输出被重定向时保留 "助手> "、"工具> " 各一行。
     */
    static Transcript transcript(Console console, boolean debug) {
        return console == null || debug ? Transcript.plain()
                : Transcript.forTerminal(console.charset(), Splash.ColorSupport.system() != Splash.ColorSupport.NONE);
    }

    /** 交互终端上显示启动页（像素字、版本与一条随机提示）；没有 Console 时返回 null，只打印一行版本信息。 */
    static String splash(Console console, IntSupplier columns) {
        return console == null ? null : Splash.forTerminal(console.charset(), columns.getAsInt(), HuVersion.current());
    }

    /** 启动会话的选项：-d / --debug 与 --auto 可以同时给出。 */
    record SessionFlags(boolean debug, boolean autoApprove) {
    }

    /** 没有参数或只有会话选项时返回启动会话的选项；有其他参数时返回 null，交给 options。 */
    static SessionFlags sessionFlags(String[] args) {
        boolean debug = false;
        boolean autoApprove = false;
        for (String arg : args) {
            switch (arg) {
                case "-d", "--debug" -> debug = true;
                case "--auto" -> autoApprove = true;
                default -> {
                    return null;
                }
            }
        }
        return new SessionFlags(debug, autoApprove);
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
        // 更新要替换正在运行的 jar（Windows 上被锁定），由启动脚本在启动 Java 之前处理；走到这里说明没有经过启动脚本。
        if (option.equals("update")) {
            output.println("hu: update 由 hu 启动脚本执行，直接运行 jar 时不可用；请用安装后的 hu 命令，或重新运行安装命令。");
            return 2;
        }
        output.println("hu: 不支持的参数：" + String.join(" ", args));
        output.print(usage());
        return 2;
    }

    static String usage() {
        return """
                用法：hu [选项]
                  在当前目录启动 hu-agent 交互会话；进入后输入 /help 查看命令，exit 退出。
                      hu update [--local | --pkg <安装包>]
                  更新到最新发布版（等同重新运行安装命令）；--local 在 hu-agent 源码目录中
                  构建并安装当前源码（需要 JDK 25）；--pkg 安装本地的 hu-agent.zip / hu-agent.tar.gz
                  或其所在目录，不联网。配置、授权记录和 Java 运行时都会保留。

                选项：
                  -d, --debug      调试模式：显示每次模型请求与耗时、内置 Hook 日志、微压缩、
                                   中间回复、工具调用与完整工具结果；默认只显示对话与最终回答
                      --auto       自动审批模式：需要确认的工具调用（Shell、写文件、
                                   MCP 工具等）自动批准，硬拒绝规则与工作目录检查仍然生效；
                                   会话中用 /auto 切换；可与 -d 同时使用
                  -h, --help       显示本帮助
                  -v, --version    显示版本

                配置：
                  模型接口  %s（首次运行时引导填写，删除后重新引导）
                  其他设置  %s
                  环境变量 HU_PROVIDER、HU_BASE_URL、HU_MODEL、HU_API_KEY 优先于模型配置文件。
                  项目级 Hook、MCP 与技能读取当前目录的 .agents/。
                """.formatted(HuHome.modelFile(), HuHome.configFile());
    }
}
