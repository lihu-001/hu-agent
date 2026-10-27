package dev.huagent.app;

import dev.huagent.app.HooksMain;
import dev.huagent.app.SkillMain;
import dev.huagent.app.SubagentMain;
import dev.huagent.app.TaskMain;
import dev.huagent.app.ToolUseMain;
import dev.huagent.background.BackgroundManager;
import dev.huagent.background.BackgroundTools;
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
import dev.huagent.memory.MemoryDeleteTool;
import dev.huagent.memory.MemoryEntry;
import dev.huagent.memory.MemoryReadTool;
import dev.huagent.memory.MemoryStore;
import dev.huagent.memory.MemoryWriteTool;
import dev.huagent.model.ModelClient;
import dev.huagent.model.OpenAiClient;
import dev.huagent.permission.PermissionGate;
import dev.huagent.permission.PermissionPolicy;
import dev.huagent.skill.LoadSkillTool;
import dev.huagent.skill.SkillCatalog;
import dev.huagent.subagent.SubagentRunner;
import dev.huagent.subagent.TaskTool;
import dev.huagent.task.TaskRecord;
import dev.huagent.task.TaskStatus;
import dev.huagent.task.TaskStore;
import dev.huagent.task.TaskTools;
import dev.huagent.todo.TodoList;
import dev.huagent.todo.TodoReminder;
import dev.huagent.todo.TodoWriteTool;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ShellTool;
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

public final class BackgroundMain {
    public static final String BACKGROUND_RULE = "耗时较长的命令（安装依赖、完整构建、长时间测试等）用 background_run 在后台启动，"
            + "拿到任务 ID 后继续做不依赖其结果的事；完成结果会以 <background-results> 附在之后的消息中自动送达，"
            + "不要用 shell 循环等待或反复调用 background_check 轮询。几秒内能结束、需要立即看结果的命令直接用 shell。"
            + "后台任务只属于本次会话，退出时仍在运行的会被取消。";

    private BackgroundMain() {
    }

    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input, PrintWriter output)
            throws IOException {
        return createLoop(config, client, input, output, ProjectDirectory.resolve(), List.of(),
                HookTrustStore.userStore(), new TodoList());
    }

    /** 显式目录、信任存储和清单用于嵌入与隔离测试；后台任务只在会话内存中。 */
    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input,
                                     PrintWriter output, Path projectDirectory,
                                     List<? extends AgentHook> agentCallbacks, HookTrustStore trustStore,
                                     TodoList todos) throws IOException {
        AgentHooks hooks = HooksMain.createHooks(projectDirectory, agentCallbacks, trustStore, input, output);
        SkillCatalog catalog = SkillCatalog.scan(projectDirectory, warning -> print(output, warning));
        print(output, "技能> 已加载目录 " + catalog.skills().size() + " 个：" + (catalog.skills().isEmpty() ? "无"
                : String.join("、", catalog.skills().stream().map(skill -> skill.name()).toList())));
        WorkspaceFiles files = new WorkspaceFiles(config.workDirectory());
        MemoryStore memories = new MemoryStore(files.root().resolve(MemoryStore.DIRECTORY_NAME));
        List<MemoryEntry> entries = memories.list(warning -> print(output, warning));
        print(output, "记忆> 已加载索引 " + entries.size() + " 条：" + (entries.isEmpty() ? "无"
                : String.join("、", entries.stream().map(MemoryEntry::name).toList())));
        TaskStore tasks = new TaskStore(files.root().resolve(TaskStore.DIRECTORY_NAME));
        List<TaskRecord> saved = tasks.list(warning -> print(output, warning));
        long completed = saved.stream().filter(task -> task.status() == TaskStatus.COMPLETED).count();
        print(output, "任务> " + TaskStore.DIRECTORY_NAME + " 中有 " + saved.size() + " 个任务，已完成 " + completed + " 个");
        PermissionGate gate = new PermissionGate(new PermissionPolicy(files), input, output);
        // 子代理与 S10 相同：没有后台工具，子代理结束时不会留下无人接收的后台命令。
        ToolRegistry childTools = ToolUseMain.createTools(config, gate, hooks, List.of(new LoadSkillTool(catalog)));
        SubagentRunner runner = new SubagentRunner(client, childTools, config.maxModelCalls(),
                SkillMain.systemPrompt(SubagentMain.SUBAGENT_PROMPT, catalog), output);
        ContextCompactor compactor = new ContextCompactor(client,
                new TranscriptStore(files.root().resolve(TranscriptStore.DIRECTORY_NAME)), files.root(),
                config.compactThresholdChars(), config.compactKeepToolResults(),
                () -> todos.items().isEmpty() ? null : todos.render());
        // 后台命令用独立的 ShellTool：同一工作目录与输出上限，超时单独配置。
        ShellTool backgroundShell = new ShellTool(files.root(), config.backgroundTimeout(), config.maxOutputChars());
        BackgroundManager background = new BackgroundManager(backgroundShell, line -> print(output, line));
        List<AgentTool> extra = new ArrayList<>(List.of(new TodoWriteTool(todos),
                new TaskTool(runner), new LoadSkillTool(catalog), new CompactTool(compactor),
                new MemoryReadTool(memories), new MemoryWriteTool(memories), new MemoryDeleteTool(memories)));
        extra.addAll(TaskTools.create(tasks, TaskTools.DEFAULT_OWNER));
        extra.addAll(BackgroundTools.create(background, "工作目录 " + files.root() + "，命令超时 "
                + config.backgroundTimeout().toSeconds() + " 秒，最多保留 " + config.maxOutputChars() + " 个输出字符。"));
        ToolRegistry registry = ToolUseMain.createTools(config, gate, hooks, extra);
        return new ChatLoop(client, registry, config.maxModelCalls(), systemPrompt(catalog, entries),
                hooks, new TodoReminder(todos), compactor, background);
    }

    /** S10 系统提示词（含任务规则与记忆索引）之后附加后台规则。 */
    public static String systemPrompt(SkillCatalog catalog, List<MemoryEntry> entries) {
        return TaskMain.systemPrompt(catalog, entries) + "\n\n" + BACKGROUND_RULE;
    }

    private static void print(PrintWriter output, String line) {
        // 后台线程也会调用；PrintWriter 内部同步，单条提示不会与其他输出交错在同一行内。
        synchronized (output) {
            output.println(line);
            output.flush();
        }
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
            output.println("S11 Background Tasks（上一课入口）：background_run 在后台执行耗时命令（超时 "
                    + config.backgroundTimeout().toSeconds() + " 秒），完成结果在下一次请求模型前自动送达。");
            output.println("保留 S03 权限、S04 Hook、S05 任务清单、S06 子代理、S07 技能、S08 压缩、S09 记忆与 S10 任务图。Hook 配置> "
                    + projectDirectory.resolve(HookConfig.DEFAULT_PATH));
            loop.run(input, output);
        } catch (IllegalArgumentException e) {
            output.println("配置错误：" + e.getMessage());
        } catch (IOException e) {
            output.println("Hook 配置读取、技能、记忆或任务目录扫描、工作目录初始化或终端读取失败（"
                    + e.getClass().getSimpleName() + "），对话结束。");
        } finally {
            output.flush();
        }
    }
}
