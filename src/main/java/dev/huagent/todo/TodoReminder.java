package dev.huagent.todo;

import java.util.Objects;

/**
 * 统计连续未更新清单的工具批次。达到阈值且清单为空或仍有未完成项时返回提醒，然后重新计数。
 * 每轮用户输入开始时清零；提醒只是提示，不强制模型调用 todo_write。
 */
public final class TodoReminder {
    public static final int DEFAULT_THRESHOLD = 3;
    public static final String TEXT = "<reminder>已连续多批工具调用未更新任务清单。"
            + "多步骤任务请用 todo_write 提交最新完整清单并标记进度。</reminder>";

    private final TodoList todos;
    private final int threshold;
    private long seenVersion;
    private int staleBatches;

    public TodoReminder(TodoList todos) {
        this(todos, DEFAULT_THRESHOLD);
    }

    public TodoReminder(TodoList todos, int threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("提醒阈值必须为正数");
        }
        this.todos = Objects.requireNonNull(todos);
        this.threshold = threshold;
        this.seenVersion = todos.version();
    }

    public void startTurn() {
        staleBatches = 0;
        seenVersion = todos.version();
    }

    /** 每批工具执行完成后调用；返回 null 表示无需提醒。 */
    public String afterToolBatch() {
        long version = todos.version();
        if (version != seenVersion) {
            seenVersion = version;
            staleBatches = 0;
            return null;
        }
        if (++staleBatches < threshold) {
            return null;
        }
        staleBatches = 0;
        return todos.items().isEmpty() || todos.hasUnfinished() ? TEXT : null;
    }
}
