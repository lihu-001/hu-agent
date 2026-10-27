package dev.huagent.workflow;

import java.time.Instant;
import java.util.Objects;

/**
 * 工作流的一个步骤：prompt 是交给子代理的任务说明；result 是该步骤完成后的最终回复。
 * result 在 completed 时必须有值，failed 时可以保存未完成说明；pending 时必须是 null。
 */
public record WorkflowStep(String prompt, StepStatus status, String result, Instant startedAt, Instant finishedAt) {

    public WorkflowStep {
        Objects.requireNonNull(prompt);
        Objects.requireNonNull(status);
    }

    public static WorkflowStep pending(String prompt) {
        return new WorkflowStep(prompt, StepStatus.PENDING, null, null, null);
    }

    public WorkflowStep withPrompt(String newPrompt) {
        return new WorkflowStep(newPrompt, status, result, startedAt, finishedAt);
    }

    public WorkflowStep withResult(String newResult) {
        return new WorkflowStep(prompt, status, newResult, startedAt, finishedAt);
    }

    public WorkflowStep markRunning(Instant now) {
        return new WorkflowStep(prompt, StepStatus.RUNNING, result, now, null);
    }

    /** 完成或失败时结束计时；failed 可以带上未完成说明。 */
    public WorkflowStep finish(StepStatus finalStatus, String finalResult, Instant now) {
        return new WorkflowStep(prompt, finalStatus, finalResult, startedAt, now);
    }

    /** 中断或进程退出时状态未知，回到 pending 等待重跑；副作用可能重复，步骤说明应写成可重复执行。 */
    public WorkflowStep reset() {
        return new WorkflowStep(prompt, StepStatus.PENDING, null, null, null);
    }
}
