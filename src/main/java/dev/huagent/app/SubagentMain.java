package dev.huagent.app;

import dev.huagent.config.AgentConfig;
import dev.huagent.config.ProjectDirectory;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.AgentHooks;
import dev.huagent.hook.HookConfig;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.loop.ChatLoop;
import dev.huagent.model.ModelClient;
import dev.huagent.model.OpenAiClient;
import dev.huagent.permission.PermissionGate;
import dev.huagent.permission.PermissionPolicy;
import dev.huagent.subagent.SubagentRunner;
import dev.huagent.subagent.TaskTool;
import dev.huagent.todo.TodoList;
import dev.huagent.todo.TodoReminder;
import dev.huagent.todo.TodoWriteTool;
import dev.huagent.tool.ToolRegistry;
import dev.huagent.tool.file.WorkspaceFiles;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class SubagentMain {
    public static final String SYSTEM_PROMPT = TodoMain.SYSTEM_PROMPT
            + "需要大量查找、阅读才能得出结论的探索性子任务，可用 task 派发给子代理，"
            + "prompt 写清目标、范围和期望的返回内容；子代理只返回总结，看不到当前对话。"
            + "简单的单步操作直接用工具完成。";

    /** 子代理的系统提示词：只完成交代的任务，最后用一段文字总结结果。 */
    public static final String SUBAGENT_PROMPT = ToolUseMain.SYSTEM_PROMPT
            + "你是被派发执行单个子任务的子代理，看不到主对话，只能根据任务说明工作。"
            + "写入、编辑和 Shell 调用同样需要用户确认，被拒绝时不要绕过。"
            + "完成后用简洁的中文总结结论、关键依据（文件路径等）和未完成的部分，这段总结就是返回给主代理的全部内容。";

    private SubagentMain() {
    }

    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input, PrintWriter output)
            throws IOException {
        return createLoop(config, client, input, output, ProjectDirectory.resolve(), List.of(),
                HookTrustStore.userStore(), new TodoList());
    }

    /** 显式目录、信任存储和清单用于嵌入与隔离测试；Hook 注册顺序与 S04 相同。 */
    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input,
                                     PrintWriter output, Path projectDirectory,
                                     List<? extends AgentHook> agentCallbacks, HookTrustStore trustStore,
                                     TodoList todos) throws IOException {
        AgentHooks hooks = HooksMain.createHooks(projectDirectory, agentCallbacks, trustStore, input, output);
        WorkspaceFiles files = new WorkspaceFiles(config.workDirectory());
        PermissionGate gate = new PermissionGate(new PermissionPolicy(files), input, output);
        // 子代理只拿五个基础工具：没有 task（不能递归派生）、没有 todo_write（不改父代理清单）。
        // 共用同一权限与 Hook 链，子代理的每次工具调用仍逐次确认并触发 Pre/PostToolUse。
        ToolRegistry childTools = ToolUseMain.createTools(config, gate, hooks);
        SubagentRunner runner = new SubagentRunner(client, childTools, config.maxModelCalls(), SUBAGENT_PROMPT, output);
        ToolRegistry tools = ToolUseMain.createTools(config, gate, hooks,
                List.of(new TodoWriteTool(todos), new TaskTool(runner)));
        return new ChatLoop(client, tools, config.maxModelCalls(), SYSTEM_PROMPT, hooks, new TodoReminder(todos));
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
            ChatLoop loop = createLoop(config, client, input, output);
            output.println("S06 Subagent（上一课入口）：模型可用 task 派发子代理，子代理拥有独立消息历史，只把总结交回主对话。");
            output.println("保留 S03 权限、S04 Hook 与 S05 任务清单。Hook 配置> "
                    + projectDirectory.resolve(HookConfig.DEFAULT_PATH));
            loop.run(input, output);
        } catch (IllegalArgumentException e) {
            output.println("配置错误：" + e.getMessage());
        } catch (IOException e) {
            output.println("Hook 配置读取、工作目录初始化或终端读取失败（"
                    + e.getClass().getSimpleName() + "），对话结束。");
        } finally {
            output.flush();
        }
    }
}
