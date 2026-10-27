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
    public static final String SYSTEM_PROMPT = "你是一个编程助手，请用中文回答。"
            + "文件查找、读取、写入和编辑优先使用 glob、read_file、write_file、edit_file，其他命令使用 shell。"
            + "文件路径以 shell 描述中的工作目录为基准，文本使用 UTF-8，glob 使用 / 分隔。"
            + "根据真实工具结果回答；工具错误可修正后再次调用，不要把失败描述为成功。";

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

    /** 在五个基础工具之后追加后续课程的工具，共用同一权限和 Hook 链。 */
    public static ToolRegistry createTools(AgentConfig config, ToolPermission permission,
                                          AgentHooks hooks, List<? extends AgentTool> extraTools) throws IOException {
        WorkspaceFiles files = new WorkspaceFiles(config.workDirectory());
        List<AgentTool> tools = new ArrayList<>(List.of(
                new ShellTool(files.root(), config.shellTimeout(), config.maxOutputChars()),
                new ReadFileTool(files), new WriteFileTool(files), new EditFileTool(files), new GlobTool(files)));
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
