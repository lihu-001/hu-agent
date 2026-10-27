package dev.huagent.app;

import dev.huagent.config.AgentConfig;
import dev.huagent.config.ProjectDirectory;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.AgentHooks;
import dev.huagent.hook.HookConfig;
import dev.huagent.hook.HookTrust;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.hook.LoggingHook;
import dev.huagent.loop.ChatLoop;
import dev.huagent.model.ModelClient;
import dev.huagent.model.OpenAiClient;
import dev.huagent.permission.PermissionGate;
import dev.huagent.permission.PermissionPolicy;
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
import java.util.ArrayList;
import java.util.List;

public final class HooksMain {
    public static final String SYSTEM_PROMPT = PermissionMain.SYSTEM_PROMPT
            + "工具执行前后有程序钩子；钩子拒绝也表示工具未执行，应尊重拒绝并等待用户的新指示。";

    private HooksMain() {
    }

    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input, PrintWriter output)
            throws IOException {
        return createLoop(config, client, input, output, ProjectDirectory.resolve());
    }

    /** 默认使用本子项目；显式目录仅供嵌入和隔离测试。 */
    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input,
                                     PrintWriter output, Path projectDirectory) throws IOException {
        return createLoop(config, client, input, output, projectDirectory, List.of());
    }

    /** 追加 Agent 的 Java Hook；保留默认日志，用户 JSON Hook 另经信任授权启用。 */
    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input,
                                     PrintWriter output, List<? extends AgentHook> agentCallbacks) throws IOException {
        return createLoop(config, client, input, output, ProjectDirectory.resolve(), agentCallbacks);
    }

    /** 注册顺序：内置日志 → 追加的 Agent Java Hook → 用户 JSON Hook。 */
    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input,
                                     PrintWriter output, Path projectDirectory,
                                     List<? extends AgentHook> agentCallbacks) throws IOException {
        return createLoop(config, client, input, output, projectDirectory, agentCallbacks, HookTrustStore.userStore());
    }

    /** 显式信任存储用于测试，避免测试读写真实用户授权。 */
    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input,
                                     PrintWriter output, Path projectDirectory,
                                     List<? extends AgentHook> agentCallbacks, HookTrustStore trustStore) throws IOException {
        AgentHooks hooks = createHooks(projectDirectory, agentCallbacks, trustStore, input, output);
        WorkspaceFiles files = new WorkspaceFiles(config.workDirectory());
        PermissionGate gate = new PermissionGate(new PermissionPolicy(files), input, output);
        ToolRegistry tools = ToolUseMain.createTools(config, gate, hooks);
        return new ChatLoop(client, tools, config.maxModelCalls(), SYSTEM_PROMPT, hooks);
    }

    /** 注册顺序：内置日志 → 追加的 Agent Java Hook → 经信任授权的用户 JSON Hook；供后续入口复用。 */
    public static AgentHooks createHooks(Path projectDirectory, List<? extends AgentHook> agentCallbacks,
                                         HookTrustStore trustStore, BufferedReader input, PrintWriter output)
            throws IOException {
        return createHooks(projectDirectory, agentCallbacks, trustStore, input, output, true);
    }

    /** logging 为 false 时（hu 默认）不注册内置日志，其余 Hook 不变。 */
    public static AgentHooks createHooks(Path projectDirectory, List<? extends AgentHook> agentCallbacks,
                                         HookTrustStore trustStore, BufferedReader input, PrintWriter output,
                                         boolean logging) throws IOException {
        List<AgentHook> callbacks = new ArrayList<>();
        if (logging) {
            callbacks.add(new LoggingHook(output));
        }
        callbacks.addAll(agentCallbacks);
        callbacks.addAll(HookTrust.load(projectDirectory, input, output, trustStore));
        return new AgentHooks(callbacks, message -> {
            output.println(message);
            output.flush();
        });
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
            ChatLoop loop = createLoop(config, client, input, output, projectDirectory);
            output.println("S04 Hooks（上一课入口）：UserPromptSubmit / PreToolUse / PostToolUse / Stop。");
            output.println("用户提示词记录前、工具调用前后、本轮任务完成后触发；保留 S03 权限检查。");
            output.println("Hook 配置> " + projectDirectory.resolve(HookConfig.DEFAULT_PATH)
                    + "（启动时加载，用户命令需独立信任授权）");
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
