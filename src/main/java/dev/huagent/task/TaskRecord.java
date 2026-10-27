package dev.huagent.task;

import java.util.List;
import java.util.Objects;

/** 一条持久化任务；blockedBy 是必须先完成的任务 ID，owner 是认领者，未认领时为 null。 */
public record TaskRecord(String id, String subject, String description, TaskStatus status, String owner,
                         List<String> blockedBy) {
    public TaskRecord {
        Objects.requireNonNull(id);
        Objects.requireNonNull(subject);
        description = description == null ? "" : description;
        Objects.requireNonNull(status);
        blockedBy = List.copyOf(blockedBy);
    }

    TaskRecord withBlockedBy(List<String> dependencies) {
        return new TaskRecord(id, subject, description, status, owner, dependencies);
    }

    TaskRecord withStatus(TaskStatus next, String nextOwner) {
        return new TaskRecord(id, subject, description, next, nextOwner, blockedBy);
    }
}
