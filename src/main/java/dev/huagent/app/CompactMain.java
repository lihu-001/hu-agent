package dev.huagent.app;

import dev.huagent.compact.CompactTool;
import dev.huagent.compact.ContextCompactor;
import dev.huagent.compact.TranscriptStore;
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
import dev.huagent.skill.LoadSkillTool;
import dev.huagent.skill.SkillCatalog;
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

public final class CompactMain {
    public static final String COMPACT_RULE = "对话变长时程序会自动压缩：较早的长工具结果会被占位说明代替，需要时重新调用工具；"
            + "超过阈值时历史会被总结。长任务完成一个阶段或切换话题时，可调用 compact 主动压缩。";

    private CompactMain() {
    }

    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input, PrintWriter output)
            throws IOException {
        return createLoop(config, client, input, output, ProjectDirectory.resolve(), List.of(),
                HookTrustStore.userStore(), new TodoList());
    }

    /** 显式目录、信任存储和清单用于嵌入与隔离测试；完整记录写入工作目录的 .transcripts。 */
    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input,
                                     PrintWriter output, Path projectDirectory,
                                     List<? extends AgentHook> agentCallbacks, HookTrustStore trustStore,
                                     TodoList todos) throws IOException {
        AgentHooks hooks = HooksMain.createHooks(projectDirectory, agentCallbacks, trustStore, input, output);
        SkillCatalog catalog = SkillCatalog.scan(projectDirectory, warning -> {
            output.println(warning);
            output.flush();
        });
        output.println("技能> 已加载目录 " + catalog.skills().size() + " 个：" + (catalog.skills().isEmpty() ? "无"
                : String.join("、", catalog.skills().stream().map(skill -> skill.name()).toList())));
        output.flush();
        WorkspaceFiles files = new WorkspaceFiles(config.workDirectory());
        PermissionGate gate = new PermissionGate(new PermissionPolicy(files), input, output);
        // 子代理与 S07 相同：历史短且每次 task 结束即丢弃，不做压缩，也没有 compact。
        ToolRegistry childTools = ToolUseMain.createTools(config, gate, hooks, List.of(new LoadSkillTool(catalog)));
        SubagentRunner runner = new SubagentRunner(client, childTools, config.maxModelCalls(),
                SkillMain.systemPrompt(SubagentMain.SUBAGENT_PROMPT, catalog), output);
        // 任务清单由程序保存，压缩后原样附在摘要后面，不依赖模型复述。
        ContextCompactor compactor = new ContextCompactor(client,
                new TranscriptStore(files.root().resolve(TranscriptStore.DIRECTORY_NAME)), files.root(),
                config.compactThresholdChars(), config.compactKeepToolResults(),
                () -> todos.items().isEmpty() ? null : todos.render());
        ToolRegistry tools = ToolUseMain.createTools(config, gate, hooks, List.of(new TodoWriteTool(todos),
                new TaskTool(runner), new LoadSkillTool(catalog), new CompactTool(compactor)));
        return new ChatLoop(client, tools, config.maxModelCalls(), systemPrompt(catalog),
                hooks, new TodoReminder(todos), compactor);
    }

    public static String systemPrompt(SkillCatalog catalog) {
        return SkillMain.systemPrompt(SubagentMain.SYSTEM_PROMPT + COMPACT_RULE, catalog);
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
            output.println("S08 Context Compact（上一课入口）：较早的长工具结果以占位说明代替；对话约超过 "
                    + config.compactThresholdChars() + " 字符或模型调用 compact 时，保存完整记录并总结历史。");
            output.println("保留 S03 权限、S04 Hook、S05 任务清单、S06 子代理与 S07 技能。完整记录> "
                    + config.workDirectory().resolve(TranscriptStore.DIRECTORY_NAME) + "，Hook 配置> "
                    + projectDirectory.resolve(HookConfig.DEFAULT_PATH));
            loop.run(input, output);
        } catch (IllegalArgumentException e) {
            output.println("配置错误：" + e.getMessage());
        } catch (IOException e) {
            output.println("Hook 配置读取、技能目录扫描、工作目录初始化或终端读取失败（"
                    + e.getClass().getSimpleName() + "），对话结束。");
        } finally {
            output.flush();
        }
    }
}
