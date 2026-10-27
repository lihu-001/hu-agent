package dev.huagent.team;

import dev.huagent.compact.ContextCompactor;
import dev.huagent.hook.AgentHooks;
import dev.huagent.model.ModelClient;
import dev.huagent.todo.TodoReminder;
import dev.huagent.tool.ToolRegistry;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;

/**
 * 组成一个队友 Agent 的部件：模型、工具、系统提示词、Hook、可选的任务清单提醒与压缩器。
 * 每个队友一份，历史、清单与压缩状态不与 Lead 或其他队友共享；output 已带 "[name]" 前缀。
 */
public record TeammateSetup(ModelClient client, ToolRegistry tools, String systemPrompt, AgentHooks hooks,
                            TodoReminder reminder, ContextCompactor compactor, int maxModelCalls,
                            PrintWriter output) {
    public TeammateSetup {
        Objects.requireNonNull(client);
        Objects.requireNonNull(tools);
        Objects.requireNonNull(systemPrompt);
        Objects.requireNonNull(hooks);
        Objects.requireNonNull(output);
        if (maxModelCalls <= 0) {
            throw new IllegalArgumentException("队友每轮模型调用上限必须为正数");
        }
    }

    /**
     * 为队友创建部件；role 进入队友的系统提示词，team 用于 send_message，output 已带前缀。
     * worktree 为 null 时队友与 Lead 共用工作目录，否则文件工具、Shell 和权限检查都以该 worktree 为根。
     */
    @FunctionalInterface
    public interface Factory {
        TeammateSetup create(String name, String role, WorktreeManager.Worktree worktree, TeamManager team,
                             PrintWriter output) throws IOException;
    }

    /** 带 "[name]" 前缀的队友输出，整行写入共享终端。 */
    public static PrintWriter prefixed(PrintWriter target, String name) {
        return PrefixWriter.wrap(target, name);
    }
}
