package dev.huagent.todo;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话内存中的任务清单。模型每次提交完整清单整体替换；校验失败时保持原状。
 * 不写文件、不跨会话保存，version 只用于判断最近是否更新过。
 */
public final class TodoList {
    public static final int MAX_ITEMS = 20;
    public static final int MAX_CONTENT_CHARS = 200;

    private List<TodoItem> items = List.of();
    private long version;

    public synchronized void replace(List<TodoItem> next) {
        if (next.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("任务最多 " + MAX_ITEMS + " 项。");
        }
        List<TodoItem> normalized = new ArrayList<>();
        int inProgress = 0;
        for (TodoItem item : next) {
            String content = StrUtil.trim(item.content());
            if (StrUtil.isEmpty(content)) {
                throw new IllegalArgumentException("任务内容不能为空。");
            }
            if (content.length() > MAX_CONTENT_CHARS) {
                throw new IllegalArgumentException("单项任务内容最多 " + MAX_CONTENT_CHARS + " 字符。");
            }
            if (item.status() == TodoStatus.IN_PROGRESS && ++inProgress > 1) {
                throw new IllegalArgumentException("同一时间只能有一项 in_progress。");
            }
            normalized.add(new TodoItem(content, item.status()));
        }
        items = List.copyOf(normalized);
        version++;
    }

    public synchronized List<TodoItem> items() {
        return items;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized boolean hasUnfinished() {
        return items.stream().anyMatch(item -> item.status() != TodoStatus.COMPLETED);
    }

    public synchronized String render() {
        if (items.isEmpty()) {
            return "任务清单为空。";
        }
        long done = items.stream().filter(item -> item.status() == TodoStatus.COMPLETED).count();
        StringBuilder text = new StringBuilder("任务清单（" + done + "/" + items.size() + " 完成）：");
        for (TodoItem item : items) {
            text.append('\n').append(item.status().marker()).append(' ').append(item.content());
        }
        return text.toString();
    }
}
