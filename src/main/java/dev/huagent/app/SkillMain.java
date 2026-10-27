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

public final class SkillMain {
    public static final String SKILL_RULE = "任务与技能目录中的某个技能匹配时，先用 load_skill 读取它的完整说明，再按说明执行；"
            + "不要凭技能名称猜测内容，与任务无关的技能不必加载。";

    private SkillMain() {
    }

    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input, PrintWriter output)
            throws IOException {
        return createLoop(config, client, input, output, ProjectDirectory.resolve(), List.of(),
                HookTrustStore.userStore(), new TodoList());
    }

    /** 显式目录、信任存储和清单用于嵌入与隔离测试；技能从 projectDirectory/.agents/skills 扫描。 */
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
        // 子代理在五个基础工具外也能加载技能；仍没有 task 与 todo_write。
        ToolRegistry childTools = ToolUseMain.createTools(config, gate, hooks, List.of(new LoadSkillTool(catalog)));
        SubagentRunner runner = new SubagentRunner(client, childTools, config.maxModelCalls(),
                systemPrompt(SubagentMain.SUBAGENT_PROMPT, catalog), output);
        ToolRegistry tools = ToolUseMain.createTools(config, gate, hooks,
                List.of(new TodoWriteTool(todos), new TaskTool(runner), new LoadSkillTool(catalog)));
        return new ChatLoop(client, tools, config.maxModelCalls(), systemPrompt(SubagentMain.SYSTEM_PROMPT, catalog),
                hooks, new TodoReminder(todos));
    }

    /** 只把技能目录（名称 + 描述）拼进系统提示词；没有技能时与 S06 提示词相同。 */
    public static String systemPrompt(String base, SkillCatalog catalog) {
        String rendered = catalog.renderCatalog();
        return rendered.isEmpty() ? base : base + SKILL_RULE + "\n\n" + rendered;
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
            output.println("S07 Skill Loading（上一课入口）：系统提示词只列技能名称与用途，模型用 load_skill 按需读取完整说明。");
            output.println("保留 S03 权限、S04 Hook、S05 任务清单与 S06 子代理。技能目录> "
                    + projectDirectory.resolve(SkillCatalog.DEFAULT_PATH) + "，Hook 配置> "
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
