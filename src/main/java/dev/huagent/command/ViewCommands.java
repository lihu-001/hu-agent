package dev.huagent.command;

import dev.huagent.background.BackgroundTools;
import dev.huagent.config.AgentConfig;
import dev.huagent.config.HuHome;
import dev.huagent.hook.HookConfig;
import dev.huagent.mcp.McpConfig;
import dev.huagent.mcp.McpManager;
import dev.huagent.memory.MemoryEntry;
import dev.huagent.memory.MemoryStore;
import dev.huagent.schedule.ScheduleRecord;
import dev.huagent.schedule.ScheduleStore;
import dev.huagent.skill.Skill;
import dev.huagent.skill.SkillCatalog;
import dev.huagent.task.TaskRecord;
import dev.huagent.task.TaskStore;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.workflow.WorkflowRecord;
import dev.huagent.workflow.WorkflowStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读查看命令：每次执行都从存储重读（文件是唯一数据源），不改变任何状态。
 * 存储中的无效文件与工具一样告警跳过，告警附在输出末尾。
 */
final class ViewCommands {
    private static final int MAX_DESCRIPTION_CHARS = 100;

    private ViewCommands() {
    }

    static List<SlashCommand> create(CommandSources sources) {
        List<SlashCommand> commands = new ArrayList<>(List.of(status(sources), tools(), skills(sources),
                mcp(sources), hooks(sources), memory(sources), todos(sources), tasks(sources), background(sources),
                schedules(sources)));
        if (sources.workflows() != null) {
            commands.add(workflows(sources.workflows()));
        }
        if (sources.team() != null) {
            commands.add(SlashCommand.local("team", "查看队友状态与各自持有的任务",
                    (argument, context) -> CommandResult.print("团队> " + sources.team().render())));
        }
        return commands;
    }

    /** api-key 只说明已配置，不显示任何字符。 */
    static SlashCommand status(CommandSources sources) {
        return SlashCommand.local("status", "查看模型、工作目录、各项上限与已启用的机制", (argument, context) -> {
            AgentConfig config = sources.config();
            StringBuilder text = new StringBuilder("状态> 模型 ").append(config.model());
            if (sources.goal() != null) {
                text.append("，目标判断器 ").append(config.evaluatorModel())
                        .append("（每轮最多自动续跑 ").append(sources.goal().blockCap()).append(" 次）");
            }
            text.append("，接口 ").append(config.baseUrl()).append("，api-key 已配置（不显示）")
                    .append("\n状态> 工作目录 ").append(config.workDirectory().toAbsolutePath().normalize())
                    .append("，项目配置 ").append(sources.projectDirectory().resolve(".agents"))
                    .append("\n状态> 每轮最多请求模型 ").append(config.maxModelCalls()).append(" 次；Shell 超时 ")
                    .append(config.shellTimeout().toSeconds()).append(" 秒、输出上限 ").append(config.maxOutputChars())
                    .append(" 字符；后台命令超时 ").append(config.backgroundTimeout().toSeconds()).append(" 秒；计划每 ")
                    .append(config.schedulerPollSeconds()).append(" 秒检查一次")
                    .append("\n状态> 自动压缩阈值 ").append(config.compactThresholdChars()).append(" 字符，保留最近 ")
                    .append(config.compactKeepToolResults()).append(" 个工具结果原文")
                    .append("\n状态> 团队模式").append(sources.team() == null ? "未开启"
                            : "已开启（" + sources.team().names().size() + " 个队友）")
                    .append("；MCP server ").append(sources.mcp().servers().size()).append(" 个；工作流")
                    .append(sources.workflows() == null ? "未启用" : "已启用")
                    .append("\n状态> 配置来自 ").append(HuHome.configFile())
                    .append(" 与 HU_* 环境变量，修改后需重启。");
            return CommandResult.print(text.toString());
        });
    }

    static SlashCommand tools() {
        return SlashCommand.local("tools", "查看主 Agent 当前的工具（子代理与队友的工具更少）", (argument, context) -> {
            StringBuilder text = new StringBuilder("工具> 共 " + context.tools().size() + " 个：");
            for (ToolDefinition tool : context.tools()) {
                text.append("\n  ").append(tool.name()).append(" — ").append(brief(tool.description()));
            }
            return CommandResult.print(text.toString());
        });
    }

    static SlashCommand skills(CommandSources sources) {
        return SlashCommand.local("skills", "查看技能目录（名称与用途）", (argument, context) -> {
            SkillCatalog catalog = sources.catalog();
            String directory = sources.projectDirectory().resolve(SkillCatalog.DEFAULT_PATH).toString();
            if (catalog.skills().isEmpty()) {
                return CommandResult.print("技能> 没有技能（目录 " + directory + "）。");
            }
            StringBuilder text = new StringBuilder("技能> " + catalog.skills().size() + " 个（目录 " + directory
                    + "）。模型按需用 load_skill 读取正文；也可以输入 /<名称> [补充说明] 直接调用：");
            for (Skill skill : catalog.skills()) {
                text.append("\n  ").append(skill.name()).append(" — ").append(brief(skill.description()));
            }
            return CommandResult.print(text.append("\n技能> 正文修改即时生效；增删技能或改名需重启。").toString());
        });
    }

    static SlashCommand mcp(CommandSources sources) {
        return SlashCommand.local("mcp", "查看 MCP server 的连接状态、工具与免确认设置", (argument, context) -> {
            String file = sources.projectDirectory().resolve(McpConfig.DEFAULT_PATH).toString();
            List<McpManager.ServerStatus> servers = sources.mcp().status();
            if (servers.isEmpty()) {
                return CommandResult.print("MCP> 本会话没有已连接的 MCP server（配置 " + file
                        + " 不存在、未授权启动或全部启动失败）；修改配置或授权需重启。");
            }
            StringBuilder text = new StringBuilder("MCP> 配置 " + file + "，本会话启动了 " + servers.size()
                    + " 个 server（断开后不自动重连）：");
            for (McpManager.ServerStatus server : servers) {
                text.append("\n  ").append(server.name()).append(server.connected() ? "（已连接）" : "（已断开）")
                        .append(" 工具 ").append(server.tools().size()).append(" 个");
                if (!server.tools().isEmpty()) {
                    text.append("：").append(String.join("、", server.tools().stream()
                            .map(tool -> server.allowed().contains(tool) ? tool + "（免确认）" : tool).toList()));
                }
            }
            return CommandResult.print(text.append("\nMCP> 工具调用名为 mcp__<server>__<工具>，未放行的每次调用逐次确认。")
                    .toString());
        });
    }

    static SlashCommand hooks(CommandSources sources) {
        return SlashCommand.local("hooks", "查看主 Agent 生效的 Hook（按执行顺序）与用户配置位置", (argument, context) -> {
            List<String> descriptions = sources.hooks().descriptions();
            StringBuilder text = new StringBuilder("Hook> 主 Agent 的回调按执行顺序共 " + descriptions.size() + " 个：");
            for (int i = 0; i < descriptions.size(); i++) {
                text.append("\n  ").append(i + 1).append(". ").append(descriptions.get(i));
            }
            return CommandResult.print(text.append("\nHook> 用户命令配置 ")
                    .append(sources.projectDirectory().resolve(HookConfig.DEFAULT_PATH))
                    .append("，经信任确认才生效；修改后需重启并重新确认。").toString());
        });
    }

    static SlashCommand memory(CommandSources sources) {
        return SlashCommand.local("memory", "查看 " + MemoryStore.DIRECTORY_NAME + " 中的记忆索引", (argument, context) -> {
            List<String> warnings = new ArrayList<>();
            List<MemoryEntry> entries = sources.memories().list(warnings::add);
            StringBuilder text = new StringBuilder("记忆> " + MemoryStore.DIRECTORY_NAME + " 中有 " + entries.size()
                    + " 条（以文件为准；系统提示词中的索引是启动时的快照，本次写入下次启动生效）"
                    + (entries.isEmpty() ? "。" : "："));
            for (MemoryEntry entry : entries) {
                text.append("\n  ").append(entry.name()).append(" [").append(entry.type().value()).append("] ")
                        .append(brief(entry.description()));
            }
            return CommandResult.print(withWarnings(text, warnings));
        });
    }

    static SlashCommand todos(CommandSources sources) {
        return SlashCommand.local("todos", "查看会话任务清单（todo_write 维护）",
                (argument, context) -> CommandResult.print("清单> " + sources.todos().render()));
    }

    static SlashCommand tasks(CommandSources sources) {
        return SlashCommand.local("tasks", "查看 " + TaskStore.DIRECTORY_NAME + " 中的持久化任务与依赖", (argument, context) -> {
            List<String> warnings = new ArrayList<>();
            List<TaskRecord> tasks = sources.tasks().list(warnings::add);
            StringBuilder text = new StringBuilder("任务> " + TaskStore.DIRECTORY_NAME + " 中有 " + tasks.size() + " 个"
                    + (tasks.isEmpty() ? "。" : "：\n" + sources.tasks().render(tasks)));
            return CommandResult.print(withWarnings(text, warnings));
        });
    }

    static SlashCommand background(CommandSources sources) {
        return SlashCommand.local("bg", "查看本次会话的后台命令（不含定时计划的运行）",
                (argument, context) -> CommandResult.print("后台> "
                        + BackgroundTools.render(sources.background().list())));
    }

    static SlashCommand schedules(CommandSources sources) {
        return SlashCommand.local("schedules", "查看 " + ScheduleStore.DIRECTORY_NAME + " 中的定时计划", (argument, context) -> {
            List<String> warnings = new ArrayList<>();
            List<ScheduleRecord> schedules = sources.schedules().list(warnings::add);
            StringBuilder text = new StringBuilder("计划> " + ScheduleStore.render(schedules, sources.zone()));
            return CommandResult.print(withWarnings(text, warnings));
        });
    }

    static SlashCommand workflows(WorkflowStore store) {
        return SlashCommand.local("workflows", "查看 " + WorkflowStore.DIRECTORY_NAME + " 中的工作流与进度", (argument, context) -> {
            List<String> warnings = new ArrayList<>();
            List<WorkflowRecord> workflows = store.list(warnings::add);
            StringBuilder text = new StringBuilder("工作流> " + WorkflowStore.render(workflows));
            return CommandResult.print(withWarnings(text, warnings));
        });
    }

    /** 描述只取第一行，过长截断。 */
    static String brief(String description) {
        String line = description == null ? "" : description.strip().lines().findFirst().orElse("");
        return line.length() > MAX_DESCRIPTION_CHARS ? line.substring(0, MAX_DESCRIPTION_CHARS) + "…" : line;
    }

    private static String withWarnings(StringBuilder text, List<String> warnings) {
        warnings.forEach(warning -> text.append("\n").append(warning));
        return text.toString();
    }
}
