package dev.huagent.command;

import dev.huagent.background.BackgroundManager;
import dev.huagent.compact.ContextCompactor;
import dev.huagent.compact.TranscriptStore;
import dev.huagent.config.AgentConfig;
import dev.huagent.goal.GoalController;
import dev.huagent.hook.AgentHooks;
import dev.huagent.mcp.McpManager;
import dev.huagent.memory.MemoryStore;
import dev.huagent.schedule.ScheduleStore;
import dev.huagent.skill.SkillCatalog;
import dev.huagent.task.TaskStore;
import dev.huagent.team.TeamManager;
import dev.huagent.todo.TodoList;
import dev.huagent.workflow.WorkflowStore;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 组装点交给命令的会话部件，全部属于主 Agent（团队模式为 Lead）：查看命令读取的存储与管理器，
 * 以及 /clear、/compact、/export 用到的清单、目标、压缩器与完整记录。
 * workflows、team、goal 为 null 表示本会话没有该机制，对应命令不注册。
 */
public record CommandSources(AgentConfig config, Path projectDirectory, SkillCatalog catalog, McpManager mcp,
                             AgentHooks hooks, MemoryStore memories, TodoList todos, TaskStore tasks,
                             BackgroundManager background, ScheduleStore schedules, ZoneId zone,
                             WorkflowStore workflows, TeamManager team, GoalController goal,
                             ContextCompactor compactor, TranscriptStore transcripts) {
    public CommandSources {
        Objects.requireNonNull(config);
        Objects.requireNonNull(projectDirectory);
        Objects.requireNonNull(catalog);
        Objects.requireNonNull(mcp);
        Objects.requireNonNull(hooks);
        Objects.requireNonNull(memories);
        Objects.requireNonNull(todos);
        Objects.requireNonNull(tasks);
        Objects.requireNonNull(background);
        Objects.requireNonNull(schedules);
        Objects.requireNonNull(zone);
        Objects.requireNonNull(compactor);
        Objects.requireNonNull(transcripts);
    }
}
