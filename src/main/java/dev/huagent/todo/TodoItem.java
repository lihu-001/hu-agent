package dev.huagent.todo;

import java.util.Objects;

public record TodoItem(String content, TodoStatus status) {
    public TodoItem {
        Objects.requireNonNull(content);
        Objects.requireNonNull(status);
    }
}
