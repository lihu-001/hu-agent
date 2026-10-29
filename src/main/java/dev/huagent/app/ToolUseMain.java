package dev.huagent.app;

import dev.huagent.config.AgentConfig;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.AgentHooks;
import dev.huagent.loop.ChatLoop;
import dev.huagent.model.ModelClient;
import dev.huagent.model.OpenAiClient;
import dev.huagent.permission.ToolPermission;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ShellTool;
import dev.huagent.tool.ToolRegistry;
import dev.huagent.tool.file.EditFileTool;
import dev.huagent.tool.file.GlobTool;
import dev.huagent.tool.file.GrepTool;
import dev.huagent.tool.file.ReadFileTool;
import dev.huagent.tool.file.WorkspaceFiles;
import dev.huagent.tool.file.WriteFileTool;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ToolUseMain {
    /**
     * 基础系统提示词，主 Agent、子代理与队友共用，后续各课的规则接在“程序机制”之下。
     * 改编自 opencode 的 kimi.txt / system.txt（MIT，© 2025 opencode）与 oh-my-pi 的 system-prompt.md
     * （MIT，© 2025 Mario Zechner 等），见 THIRD_PARTY_NOTICES.md：按 hu 的工具改写、译成中文；
     * 按 oh-my-pi 对较小模型的建议，规则尽量写成“做什么”，每条一件事。
     */
    public static final String SYSTEM_PROMPT = """
            你是运行在用户电脑上的编程 Agent：用手头的工具真正完成用户的软件工程任务，也回答用户的问题。

            # 理解请求
            - 用户消息里可能有问题、任务、代码片段、日志或文件路径。读懂后照做；既能理解成问题也能理解成任务时，按任务处理。
            - 寒暄和不涉及工作目录的简单问题直接回答；其余默认动手，用工具去查、去改。
            - 结合工作目录里的项目理解用户指的是什么。用户描述界面上看到的内容时，用界面上的原文（提示语、标签、报错信息）搜索代码来定位。
            - 用户报告的错误、失败和现象就是事实，直接据此排查。

            # 使用工具
            - 涉及创建、修改或运行代码和文件时，调用工具真正去做：只写在回复里的代码不会保存，也不会生效。
            - 按文件名找用 glob，按内容搜用 grep，读文件用 read_file，局部修改用 edit_file，新建或整体重写用 write_file。shell 只用于构建、测试、git 等其他命令；查找、读取和编辑文件一律用前面这些专用工具。
            - 文件路径以 shell 描述中的工作目录为基准，文本使用 UTF-8，glob 使用 / 分隔。
            - 一次回复可以发出任意多个工具调用。互不依赖的调用（同时读几个文件、同时搜几个关键词）放在同一次回复里一起发出，这对效率非常重要。
            - 只打开确认存在的文件：路径没把握时先用 glob 或 grep 找。长文件用 read_file 的 offset 和 limit 读相关片段。
            - 结果为空、被截断或太宽泛时，换关键词、范围或工具再查，直到证据足够下结论。
            - 工具失败或文件可能被改过时，先重新读取再动手；工具报错就修正参数再调用，如实说明失败。
            - 拿到工具结果后有三种走向：继续做；告诉用户已完成或失败；向用户要工具查不到的信息。

            # 修改已有代码
            - 先用 grep、glob、read_file 读懂相关代码，找准真正的目标再改。
            - 修 bug：看报错或失败的测试，找到根因再修；用户提到的失败测试改完后必须通过。
            - 加功能：沿用已有的结构和写法，尽量少改动现有代码；项目有测试时补上测试。
            - 重构：接口变了就更新所有调用处，测试里的已有逻辑保持不变。
            - 改动保持最小，只做用户要的事；沿用周围代码的命名、注释密度和风格。
            - 不认识的文件和改动可能是用户的工作：删除或覆盖前先查清楚，只回退自己做的改动。
            - git commit、push、reset、rebase、checkout -- 等改变仓库状态的命令，只在用户明确要求时执行。
            - 只读写工作目录内的文件，用户要求时才碰工作目录之外的路径。
            - 改了 AGENTS.md 里描述的结构、命令或约定时，同步更新 AGENTS.md。

            # 验证与交付
            - 改完运行相关的构建或测试，看到真实输出再下结论；运行不了时说明原因。
            - 完成指用户要的行为端到端可用。交付真实实现：桩代码、占位实现和 TODO 都不算完成。
            - 还有能推进的工作就继续做；确实需要缩小范围时，先征得用户同意。
            - 回复默认用中文，用户用其他语言时跟随用户。最终回复先说结果，再给必要的细节，提到文件时写清路径。

            # 程序机制
            """;

    private ToolUseMain() {
    }

    public static ToolRegistry createTools(AgentConfig config) throws IOException {
        return createTools(config, call -> null);
    }

    public static ToolRegistry createTools(AgentConfig config, ToolPermission permission) throws IOException {
        return createTools(config, permission, AgentHooks.empty());
    }

    public static ToolRegistry createTools(AgentConfig config, ToolPermission permission,
                                          List<? extends AgentHook> hooks, Consumer<String> hookWarnings) throws IOException {
        return createTools(config, permission, new AgentHooks(hooks, hookWarnings));
    }

    public static ToolRegistry createTools(AgentConfig config, ToolPermission permission,
                                          AgentHooks hooks) throws IOException {
        return createTools(config, permission, hooks, List.of());
    }

    /** 在六个基础工具之后追加后续课程的工具，共用同一权限和 Hook 链。 */
    public static ToolRegistry createTools(AgentConfig config, ToolPermission permission,
                                          AgentHooks hooks, List<? extends AgentTool> extraTools) throws IOException {
        WorkspaceFiles files = new WorkspaceFiles(config.workDirectory());
        List<AgentTool> tools = new ArrayList<>(List.of(
                new ShellTool(files.root(), config.shellTimeout(), config.maxOutputChars()),
                new ReadFileTool(files), new WriteFileTool(files), new EditFileTool(files), new GlobTool(files),
                new GrepTool(files)));
        tools.addAll(extraTools);
        return new ToolRegistry(tools, permission, hooks);
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
            output.println("S02 Tool Use：在对话基础上加入 Shell、文件工具和多工具分派。");
            new ChatLoop(client, createTools(config), config.maxModelCalls(), SYSTEM_PROMPT).run(input, output);
        } catch (IllegalArgumentException e) {
            output.println("配置错误：" + e.getMessage());
        } catch (IOException e) {
            output.println("工作目录初始化或终端读取失败（" + e.getClass().getSimpleName() + "），对话结束。");
        } finally {
            output.flush();
        }
    }
}
