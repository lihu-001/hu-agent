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

public final class TaskMain {
    public static final String TASK_RULE = "工作目录 .tasks/ 中有跨会话保存的任务图，用 task_* 工具维护："
            + "多步骤、有先后依赖或需要跨会话继续的工作，先用 task_create 创建全部任务，再用 task_update 和返回的 ID 添加依赖；"
            + "开始某个任务前 task_claim，确实做完后 task_complete。开始新会话或不确定进度时先 task_list。"
            + "几步就能做完、不需要下次继续的事用 todo_write 列步骤即可，不要为它们建任务；"
            + "做某个任务时，也可以用 todo_write 列出这个任务内部的步骤。";

    private TaskMain() {
    }

    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input, PrintWriter output)
            throws IOException {
        return createLoop(config, client, input, output, ProjectDirectory.resolve(), List.of(),
                HookTrustStore.userStore(), new TodoList());
    }

    /** 显式目录、信任存储和清单用于嵌入与隔离测试；任务保存在工作目录的 .tasks。 */
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
        // 任务图不进系统提示词：文件随时可能变化，模型用 task_list 读取当前状态；这里只在终端提示进度。
        TaskStore tasks = new TaskStore(files.root().resolve(TaskStore.DIRECTORY_NAME));
        List<TaskRecord> saved = tasks.list(warning -> print(output, warning));
        long completed = saved.stream().filter(task -> task.status() == TaskStatus.COMPLETED).count();
        print(output, "任务> " + TaskStore.DIRECTORY_NAME + " 中有 " + saved.size() + " 个任务，已完成 " + completed + " 个");
        PermissionGate gate = new PermissionGate(new PermissionPolicy(files), input, output);
        // 子代理与 S09 相同：没有任务工具，任务图只由主代理认领和完成。
        ToolRegistry childTools = ToolUseMain.createTools(config, gate, hooks, List.of(new LoadSkillTool(catalog)));
        SubagentRunner runner = new SubagentRunner(client, childTools, config.maxModelCalls(),
                SkillMain.systemPrompt(SubagentMain.SUBAGENT_PROMPT, catalog), output);
        ContextCompactor compactor = new ContextCompactor(client,
                new TranscriptStore(files.root().resolve(TranscriptStore.DIRECTORY_NAME)), files.root(),
                config.compactThresholdChars(), config.compactKeepToolResults(),
                () -> todos.items().isEmpty() ? null : todos.render());
        List<AgentTool> extra = new ArrayList<>(List.of(new TodoWriteTool(todos),
                new TaskTool(runner), new LoadSkillTool(catalog), new CompactTool(compactor),
                new MemoryReadTool(memories), new MemoryWriteTool(memories), new MemoryDeleteTool(memories)));
        extra.addAll(TaskTools.create(tasks, TaskTools.DEFAULT_OWNER));
        ToolRegistry registry = ToolUseMain.createTools(config, gate, hooks, extra);
        return new ChatLoop(client, registry, config.maxModelCalls(), systemPrompt(catalog, entries),
                hooks, new TodoReminder(todos), compactor);
    }

    /** S09 系统提示词（含记忆规则与索引）之后附加任务规则；任务内容不进系统提示词。 */
    public static String systemPrompt(SkillCatalog catalog, List<MemoryEntry> entries) {
        String base = MemoryMain.systemPrompt(catalog, List.of()) + "\n\n" + TASK_RULE;
        String index = MemoryStore.renderIndex(entries);
        return index.isEmpty() ? base : base + "\n\n" + index;
    }

    private static void print(PrintWriter output, String line) {
        output.println(line);
        output.flush();
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
            output.println("S10 Task System（上一课入口）：持久化任务保存在 "
                    + config.workDirectory().resolve(TaskStore.DIRECTORY_NAME)
                    + "，每个任务一个 JSON 文件，blockedBy 依赖全部完成后才能认领。");
            output.println("保留 S03 权限、S04 Hook、S05 任务清单、S06 子代理、S07 技能、S08 压缩与 S09 记忆。Hook 配置> "
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
