package dev.huagent.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一条持久化工作流：有序步骤序列，运行时逐步骤执行并在每步完成后落盘检查点。
 * 文件是唯一数据源；字段只有 id、name、status、steps、createdAt、updatedAt、lastError。
 */
public record WorkflowRecord(String id, String name, WorkflowStatus status, List<WorkflowStep> steps,
                             Instant createdAt, Instant updatedAt, String lastError) {

    public WorkflowRecord {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(status);
        steps = List.copyOf(steps);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
    }

    /** 第一个未完成步骤的下标；全部完成时返回 steps.size()。 */
    public int firstIncompleteIndex() {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).status() != StepStatus.COMPLETED) {
                return i;
            }
        }
        return steps.size();
    }

    public long completedCount() {
        return steps.stream().filter(step -> step.status() == StepStatus.COMPLETED).count();
    }

    public boolean resumable() {
        return status == WorkflowStatus.PENDING || status == WorkflowStatus.PAUSED
                || status == WorkflowStatus.FAILED;
    }

    public WorkflowRecord withStatus(WorkflowStatus newStatus, Instant now) {
        return new WorkflowRecord(id, name, newStatus, steps, createdAt, now, lastError);
    }

    public WorkflowRecord withSteps(List<WorkflowStep> newSteps, Instant now) {
        return new WorkflowRecord(id, name, status, newSteps, createdAt, now, lastError);
    }

    public WorkflowRecord withError(String newError, Instant now) {
        return new WorkflowRecord(id, name, status, steps, createdAt, now, newError);
    }
}
