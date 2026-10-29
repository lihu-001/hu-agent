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
import dev.huagent.harness.InputImages;
import dev.huagent.harness.ModelRetryPolicy;
import dev.huagent.harness.PrintMode;
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
import dev.huagent.prompt.GitBranch;
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
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
            PrintFlags print;
            try {
                print = printFlags(args);
            } catch (IllegalArgumentException e) {
                PrintWriter errors = progress(console);
                errors.println("hu: " + e.getMessage() + "；用法见 hu --help。");
                errors.flush();
                System.exit(2);
                return;
            }
            if (print != null) {
                int status = print(print, console, output);
                output.flush();
                // 一次性运行：会话已结束，不等模型接口的连接池等残留线程。
                System.exit(status);
                return;
            }
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
            if (editor != null && prompt instanceof FramedPrompt frame) {
                // 与 Claude Code 一样输入框固定在终端最下方，任务期间也显示，输出插到它上方（见 LineEditor.pin）。
                prompt = editor.pin(frame);
            }
            Transcript transcript = transcript(console, debug);
            if (editor != null) {
                // 与 Claude Code 一样对话区不贴着终端最左列，长行折行后与正文对齐（见 Margin）；调试模式保持原样。
                editor.margin(transcript.margin());
            }
            // 补全要用会话装配出的注册表（含技能命令）。
            CommandStarter commands = (sources, warnings) -> {
                CommandRegistry bound = CommandStarter.builtin(defaults, autoApprove).bind(sources, warnings);
                registry.set(bound);
                return bound;
            };
            run(config, input, output, debug, autoApprove, title == null ? prompt : title.prompt(prompt),
                    confirmPrompt(console, debug), transcript, splash(console, columns), commands,
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
        // 终端粘贴截图或拖入图片时输入的是文件路径：InputImages 把它作为图片发给模型。
        HarnessOptions options = new HarnessOptions(events,
                new ModelRetryPolicy(ModelRetryPolicy.DEFAULT_MAX_RETRIES), agentConsole.prompts(), debug, prompt,
                confirm, autoApprove, interrupt, projectContext, transcript, new InputImages());
        // 单 Agent 模式的确认在主线程上读这里；中断请求等回答读完再中断主线程（见 TurnInterrupt.answers）。
        BufferedReader answers = interrupt.answers(agentConsole.answers());
        TeamMain.Session session = TeamMain.createSession(config, client, answers, output,
                projectDirectory, List.of(), HookTrustStore.userStore(), new TodoList(),
                ZoneId.systemDefault(), Clock.systemDefaultZone(), true, TeamManager.DEFAULT_IDLE_SCAN,
                McpManager.Starter.of(projectDirectory, McpTrustStore.userStore(), System.getenv()),
                options, WorkflowStarter.of(config), GoalStarter.of(evaluator, config.goalBlockCap()), commands);
        List<String> servers = session.mcp().servers();
        output.println(splash == null ? "hu-agent " + HuVersion.current() + " · " + location(config)
                : location(config));
        output.println((completion ? "输入 / 列出命令，Tab 或 ↑↓ 选择，运行中按 Esc 中断" : "输入 /help 查看命令")
                + "，exit 退出。"
                + (servers.isEmpty() ? "" : "MCP 已连接：" + String.join("、", servers) + "。")
                + (session.teamEnabled() ? "团队模式已开启。" : "")
                + (debug ? "调试模式已开启。" : ""));
        if (autoApprove.enabled()) {
            output.println("自动审批模式已开启：需要确认的工具调用（Shell、写文件、MCP 工具等）自动批准，"
                    + "硬拒绝规则与工作目录检查仍然生效；/auto off 关闭，hu --no-auto 启动时不开启。");
        }
        session.run();
    }

    private static String location(AgentConfig config) {
        return "模型 " + config.model() + "（" + config.modelConfig().provider().label() + "）· 工作目录 "
                + config.workDirectory().toAbsolutePath().normalize();
    }

    /**
     * hu -p：非交互地执行一个任务后退出，用于脚本与评测框架（与 Claude Code 的 -p 相同）。返回进程退出码：
     * 0 得到最终回答，1 未配置模型、配置错误、请求失败或没有最终回答，2 没有任务。
     * 最终回答写到 result（标准输出）；过程写到标准错误：工具调用摘要、中间说明、自动批准记录、提示与错误，
     * -d 时与交互会话的调试输出相同（最终回答也在其中）。没有启动页、提示符和按键中断；首次配置引导不运行。
     * 需要确认的操作在自动审批模式下自动批准，--no-auto 时没有人回答，一律拒绝；没有信任记录的项目级 Hook 与
     * MCP server 同样不启用（先在交互会话中确认）。一轮结束会话就结束，所以不开团队模式、不启动定时计划。
     */
    static int print(PrintFlags flags, Console console, PrintWriter result) {
        PrintWriter progress = progress(console);
        try {
            // 交互终端上没给任务时不等输入（与 Claude Code 相同）；管道或重定向时读到输入结束。
            String task = flags.task() != null ? flags.task() : console == null ? readTask() : "";
            if (task.isBlank()) {
                progress.println("hu: 没有任务：在 -p 后给出任务，或不带任务参数、从标准输入传入。");
                return 2;
            }
            Path configFile = HuHome.configFile();
            Path modelFile = HuHome.modelFile();
            Map<String, String> environment = System.getenv();
            if (!AgentConfig.modelConfigured(configFile, modelFile, environment)) {
                progress.println("hu: 还没有配置模型接口：先运行一次 hu 按提示填写，或设置环境变量 HU_MODEL 与 HU_API_KEY"
                        + "（接口类型与地址用 HU_PROVIDER、HU_BASE_URL）。");
                return 1;
            }
            AgentConfig config = AgentConfig.load(configFile, modelFile, environment).withTeamEnabled(false);
            ModelClient client = ModelClients.create(config.modelConfig(), REQUEST_TIMEOUT);
            ModelClient evaluator = config.goalEvaluatorModel() == null ? client
                    : ModelClients.create(config.modelConfig().withModel(config.goalEvaluatorModel()), REQUEST_TIMEOUT);
            Path projectDirectory = ProjectDirectory.resolve();
            AutoApprove autoApprove = new AutoApprove(flags.autoApprove());
            PrintMode mode = new PrintMode(task);
            String projectContext = ProjectContext.render(config.workDirectory(), HuHome.resolve(), LocalDate.now());
            // 不画提示符；权限确认与交互会话被重定向时相同，不带颜色。
            HarnessOptions options = new HarnessOptions(new EventHub(),
                    new ModelRetryPolicy(ModelRetryPolicy.DEFAULT_MAX_RETRIES), mode.input(), flags.debug(),
                    out -> { }, confirmPrompt(null, flags.debug()), autoApprove, new TurnInterrupt(), projectContext,
                    flags.debug() ? Transcript.plain() : Transcript.headless(), new InputImages());
            ModelDefaults defaults = ModelDefaults.locate(configFile, modelFile, environment);
            TeamMain.Session session = TeamMain.createSession(config, client, PrintMode.noAnswers(), progress,
                    projectDirectory, List.of(mode), HookTrustStore.userStore(), new TodoList(),
                    ZoneId.systemDefault(), Clock.systemDefaultZone(), false, TeamManager.DEFAULT_IDLE_SCAN,
                    McpManager.Starter.of(projectDirectory, McpTrustStore.userStore(), environment),
                    options, WorkflowStarter.of(config), GoalStarter.of(evaluator, config.goalBlockCap()),
                    CommandStarter.builtin(defaults, autoApprove));
            if (flags.debug()) {
                progress.println("hu-agent " + HuVersion.current() + " · " + location(config));
            }
            session.run();
            return mode.finish(result, progress);
        } catch (IllegalArgumentException e) {
            progress.println("配置错误：" + e.getMessage());
            return 1;
        } catch (IOException e) {
            progress.println("Hook 或 MCP 配置读取、技能、记忆、任务或计划目录扫描、工作目录初始化或任务读取失败（"
                    + e.getClass().getSimpleName() + "），未完成任务。");
            return 1;
        } finally {
            progress.flush();
        }
    }

    /** hu -p 的过程输出（标准错误）：字符集与标准输出相同，交互终端用终端字符集，被重定向时用 UTF-8。 */
    private static PrintWriter progress(Console console) {
        return new PrintWriter(System.err, true, console == null ? StandardCharsets.UTF_8 : console.charset());
    }

    /** hu -p 没有任务参数时，从标准输入（管道或重定向的文件）读到结束。 */
    private static String readTask() throws IOException {
        StringWriter text = new StringWriter();
        new InputStreamReader(System.in, StandardCharsets.UTF_8).transferTo(text);
        return text.toString().strip();
    }

    /**
     * 交互终端上在输入上方画分隔线标出用户输入，线上显示模型与上下文用量（最大值取 model.properties 的 max-context-tokens）；
     * 输入输出被重定向（没有 Console）时保留 "你> "，日志里不出现分隔线。
     */
    static PromptStyle promptStyle(Console console, AgentConfig config, IntSupplier columns, AutoApprove autoApprove) {
        return console == null ? PromptStyle.plain()
                : FramedPrompt.forTerminal(console.charset(), columns, config.model(), config.maxContextTokens(),
                        autoApprove::enabled, new GitBranch(config.workDirectory()));
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

    /** 启动会话的选项：-d / --debug 与 --auto / --no-auto 可以同时给出。 */
    record SessionFlags(boolean debug, boolean autoApprove) {
    }

    /**
     * 没有参数或只有会话选项时返回启动会话的选项；有其他参数时返回 null，交给 options。
     * 默认开启自动审批模式，--no-auto 改为逐次确认；--auto 保留兼容，--auto 与 --no-auto 同时给出时以后一个为准。
     */
    static SessionFlags sessionFlags(String[] args) {
        boolean debug = false;
        boolean autoApprove = true;
        for (String arg : args) {
            switch (arg) {
                case "-d", "--debug" -> debug = true;
                case "--auto" -> autoApprove = true;
                case "--no-auto" -> autoApprove = false;
                default -> {
                    return null;
                }
            }
        }
        return new SessionFlags(debug, autoApprove);
    }

    /** hu -p 的选项：task 为 null 时从标准输入读取任务；debug 与 autoApprove 与交互会话的含义相同。 */
    record PrintFlags(String task, boolean debug, boolean autoApprove) {
    }

    /**
     * 参数中有 -p / --print 时返回非交互运行的选项，没有时返回 null（交给 options）。
     * 其余参数是会话选项（-d、--auto、--no-auto）或任务文字：多个任务参数以空格连接，-- 之后的参数都是任务文字
     * （任务以 - 开头时使用）；没有任务参数或任务只是 - 时从标准输入读取。有 -p 又有其他选项时抛出 IllegalArgumentException。
     */
    static PrintFlags printFlags(String[] args) {
        boolean print = false;
        boolean debug = false;
        boolean autoApprove = true;
        boolean literal = false;
        String unknown = null;
        List<String> words = new ArrayList<>();
        for (String arg : args) {
            if (literal) {
                words.add(arg);
                continue;
            }
            switch (arg) {
                case "-p", "--print" -> print = true;
                case "-d", "--debug" -> debug = true;
                case "--auto" -> autoApprove = true;
                case "--no-auto" -> autoApprove = false;
                case "--" -> literal = true;
                default -> {
                    if (arg.length() > 1 && arg.startsWith("-")) {
                        unknown = unknown == null ? arg : unknown;
                    } else {
                        words.add(arg);
                    }
                }
            }
        }
        if (!print) {
            return null;
        }
        if (unknown != null) {
            throw new IllegalArgumentException("-p 不支持的参数：" + unknown);
        }
        String task = String.join(" ", words);
        return new PrintFlags(words.isEmpty() || task.equals("-") ? null : task, debug, autoApprove);
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
                      hu -p [-d] [--no-auto] [--] <任务>
                  非交互地执行一个任务后退出，用于脚本与评测：最终回答写到标准输出，过程写到标准错误。
                  没有任务参数（或任务为 -）时从标准输入读取；任务以 - 开头时放在 -- 之后。
                  退出码：0 得到最终回答，1 失败或没有最终回答，2 参数错误或没有任务。
                  需要确认的操作按自动审批处理，--no-auto 时一律拒绝；团队模式与定时计划不启用。
                      hu update [--debug | -d] [--local [<源码目录>] | --pkg <安装包>]
                  更新到最新发布版（等同重新运行安装命令）；--local 构建并安装 hu-agent 源码
                  （需要 JDK 25），不给目录时用当前目录；--pkg 安装本地的 hu-agent.zip / hu-agent.tar.gz
                  或其所在目录，不联网。配置、授权记录和 Java 运行时都会保留。
                  成功时只显示更新状态，失败时显示构建、下载与安装的输出和原因；
                  --debug / -d 边执行边显示这些输出。

                选项：
                  -d, --debug      调试模式：显示每次模型请求与耗时、内置 Hook 日志、微压缩、
                                   中间回复、工具调用与完整工具结果；默认只显示对话与最终回答
                      --no-auto    关闭自动审批模式，需要确认的工具调用逐次询问。默认开启：
                                   Shell、写文件、MCP 工具等自动批准，硬拒绝规则与工作目录
                                   检查仍然生效；会话中用 /auto 切换；可与 -d 同时使用
                      --auto       开启自动审批模式（默认已开启，保留兼容）
                  -p, --print      非交互运行一个任务（见上）
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
