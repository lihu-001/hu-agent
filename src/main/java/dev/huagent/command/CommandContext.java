package dev.huagent.command;

import dev.huagent.model.ChatMessage;
import dev.huagent.tool.ToolDefinition;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

/**
 * 命令执行时可见的主循环状态：当前对话历史（第一条为系统提示词）、工具定义与终端输出。
 * 会话部件（存储、管理器）在组装时交给命令，不经过这里。/clear 与 /compact 用 replaceHistory 替换历史，
 * 主循环在命令返回后采纳；其他命令只读。只在主循环线程使用，每次执行命令新建一个。
 */
public final class CommandContext {
    private List<ChatMessage> history;
    private boolean replaced;
    private final List<ToolDefinition> tools;
    private final PrintWriter output;

    public CommandContext(List<ChatMessage> history, List<ToolDefinition> tools, PrintWriter output) {
        this.history = startsWithSystem(history);
        this.tools = List.copyOf(tools);
        this.output = Objects.requireNonNull(output);
    }

    /** 不可修改的快照。 */
    public List<ChatMessage> history() {
        return history;
    }

    /** 新历史同样必须以系统提示词开头。 */
    public void replaceHistory(List<ChatMessage> next) {
        history = startsWithSystem(next);
        replaced = true;
    }

    public boolean historyReplaced() {
        return replaced;
    }

    public List<ToolDefinition> tools() {
        return tools;
    }

    /** 命令执行期间的过程输出（如压缩进度）；最终结果由 CommandResult 返回。 */
    public PrintWriter output() {
        return output;
    }

    private static List<ChatMessage> startsWithSystem(List<ChatMessage> messages) {
        if (messages.isEmpty() || !"system".equals(messages.get(0).role())) {
            throw new IllegalArgumentException("对话历史必须以系统提示词开头");
        }
        return List.copyOf(messages);
    }
}
