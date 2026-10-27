package dev.huagent.workflow;

import dev.huagent.subagent.SubagentRunner;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 工作流执行器：从第一个未完成的步骤开始，逐步通过子代理执行，每步结束立即落盘检查点。
 * 某步失败（模型请求失败或子代理达到调用上限）时工作流标记 failed 并停止，修复后再次运行从该步骤继续；
 * 中断（InterruptedException）时步骤回到 pending、工作标记 paused，进度已保存。
 * 重跑会重新执行未完成的步骤，副作用可能重复，步骤说明应写成可重复执行。
 */
public final class WorkflowRunner {
    /** 传给后续步骤与写入汇总时，每条步骤结果的上限；存储里保留最多 MAX_RESULT_CHARS。 */
    public static final int PASS_RESULT_CHARS = 2000;
    public static final int MAX_ERROR_CHARS = 400;

    private final WorkflowStore store;
    private final SubagentRunner runner;
    private final Clock clock;
    private final PrintWriter output;

    public WorkflowRunner(WorkflowStore store, SubagentRunner runner, Clock clock, PrintWriter output) {
        this.store = Objects.requireNonNull(store);
        this.runner = Objects.requireNonNull(runner);
        this.clock = Objects.requireNonNull(clock);
        this.output = Objects.requireNonNull(output);
    }

    /** 当前时刻（由注入的时钟决定），供工具创建与取消工作流时写入时间戳。 */
    public Instant now() {
        return Instant.now(clock);
    }

    /** 执行到结束、失败或中断；返回时进度已全部落盘。 */
    public Summary run(String id) throws IOException, InterruptedException {
        WorkflowRecord workflow = store.load(id);
        if (!workflow.resumable()) {
            throw new IllegalArgumentException(switch (workflow.status()) {
                case RUNNING -> "工作流已在执行中，不能并发运行";
                case COMPLETED -> "工作流已全部完成，不能再次运行；需要重做请新建工作流";
                case CANCELLED -> "工作流已取消，不能继续；请新建工作流";
                default -> "工作流状态不允许运行";
            });
        }
        WorkflowRecord current = workflow.withStatus(WorkflowStatus.RUNNING, Instant.now(clock));
        store.save(current);
        print("工作流> " + workflow.id() + "（" + workflow.name() + "）开始执行，共 "
                + current.steps().size() + " 步，从第 " + (current.firstIncompleteIndex() + 1) + " 步继续");
        int index = current.firstIncompleteIndex();
        while (index < current.steps().size()) {
            current = executeStep(current, index);
            if (current.status() == WorkflowStatus.FAILED) {
                break;
            }
            index = current.firstIncompleteIndex();
        }
        if (current.status() == WorkflowStatus.RUNNING) {
            Instant now = Instant.now(clock);
            current = new WorkflowRecord(current.id(), current.name(), WorkflowStatus.COMPLETED,
                    current.steps(), current.createdAt(), now, null);
            store.save(current);
            print("工作流> " + current.id() + "（" + current.name() + "）全部步骤完成");
        }
        return summary(current);
    }

    /** 执行下标为 index 的步骤；返回更新后的记录（失败时状态为 failed）。 */
    private WorkflowRecord executeStep(WorkflowRecord workflow, int index)
            throws IOException, InterruptedException {
        WorkflowStep step = workflow.steps().get(index);
        Instant now = Instant.now(clock);
        WorkflowStep started = step.markRunning(now);
        WorkflowRecord current = workflow.withSteps(replace(workflow.steps(), index, started), now);
        store.save(current);
        print("工作流> 步骤 " + (index + 1) + "/" + workflow.steps().size() + " 开始执行");
        String prompt = buildPrompt(current, index);
        SubagentRunner.Result result;
        try {
            result = runner.run(workflow.name() + " 步骤 " + (index + 1), prompt);
        } catch (IOException e) {
            Instant failTime = Instant.now(clock);
            String reason = "步骤 " + (index + 1) + " 失败：子代理模型请求失败（" + e.getClass().getSimpleName() + "）";
            return markFailed(current, index, started.finish(StepStatus.FAILED, reason, failTime), reason, failTime);
        } catch (InterruptedException e) {
            // 中断时步骤状态未知：回到 pending，工作流 paused；进度已落盘，可再次运行继续。
            Instant paused = Instant.now(clock);
            WorkflowRecord pausedRecord = current.withStatus(WorkflowStatus.PAUSED, paused)
                    .withSteps(replace(current.steps(), index, started.reset()), paused);
            store.save(pausedRecord);
            print("工作流> 已中断，进度保存在 " + workflow.id() + "，再次运行可从第 " + (index + 1) + " 步继续");
            throw e;
        }
        Instant finishTime = Instant.now(clock);
        if (result.completed()) {
            WorkflowStep finished = started.finish(StepStatus.COMPLETED,
                    truncate(result.text(), WorkflowStore.MAX_RESULT_CHARS), finishTime);
            current = current.withSteps(replace(current.steps(), index, finished), finishTime);
            store.save(current);
            print("工作流> 步骤 " + (index + 1) + " 完成（子代理模型请求 " + result.modelCalls()
                    + " 次，工具调用 " + result.toolCalls() + " 个）");
            return current;
        }
        String reason = "步骤 " + (index + 1) + " 失败：子代理未完成（已达到模型调用上限）";
        return markFailed(current, index, started.finish(StepStatus.FAILED,
                truncate(result.text(), WorkflowStore.MAX_RESULT_CHARS), finishTime), reason, finishTime);
    }

    private WorkflowRecord markFailed(WorkflowRecord workflow, int index, WorkflowStep failedStep,
                                      String reason, Instant now) throws IOException {
        WorkflowRecord failed = workflow.withStatus(WorkflowStatus.FAILED, now)
                .withSteps(replace(workflow.steps(), index, failedStep), now)
                .withError(truncate(reason, MAX_ERROR_CHARS), now);
        store.save(failed);
        print("工作流> " + failed.id() + "（" + failed.name() + "）" + reason + "，已保存进度，"
                + "修复后再次运行可从第 " + (index + 1) + " 步继续");
        return failed;
    }

    /** 步骤说明加上全部前序已完成步骤的结果；每条结果截断到 PASS_RESULT_CHARS。 */
    static String buildPrompt(WorkflowRecord workflow, int index) {
        StringBuilder text = new StringBuilder(workflow.steps().get(index).prompt());
        StringBuilder prior = new StringBuilder();
        for (int i = 0; i < index; i++) {
            WorkflowStep previous = workflow.steps().get(i);
            if (previous.status() == StepStatus.COMPLETED && previous.result() != null) {
                prior.append("步骤 ").append(i + 1).append(" 的结果：\n")
                        .append(truncate(previous.result(), PASS_RESULT_CHARS)).append("\n\n");
            }
        }
        if (!prior.isEmpty()) {
            text.append("\n\n<前序步骤结果>\n").append(prior.toString().strip())
                    .append("\n</前序步骤结果>\n")
                    .append("以上是同一工作流中已完成步骤的输出，供本步骤参考；与本步骤无关时可以忽略。");
        }
        return text.toString();
    }

    private Summary summary(WorkflowRecord workflow) {
        List<Summary.StepView> steps = new ArrayList<>();
        for (int i = 0; i < workflow.steps().size(); i++) {
            WorkflowStep step = workflow.steps().get(i);
            steps.add(new Summary.StepView(i + 1, step.status(),
                    step.result() == null ? null : truncate(step.result(), PASS_RESULT_CHARS)));
        }
        return new Summary(workflow.id(), workflow.name(), workflow.status(), steps, workflow.lastError());
    }

    private static List<WorkflowStep> replace(List<WorkflowStep> steps, int index, WorkflowStep step) {
        List<WorkflowStep> next = new ArrayList<>(steps);
        next.set(index, step);
        return List.copyOf(next);
    }

    static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n…（已截断）";
    }

    private void print(String line) {
        synchronized (output) {
            output.println(line);
            output.flush();
        }
    }

    /** 一次运行结束时的快照：全部步骤的最新状态，结果截断到 PASS_RESULT_CHARS。 */
    public record Summary(String id, String name, WorkflowStatus status, List<StepView> steps, String lastError) {

        public Summary {
            steps = List.copyOf(steps);
        }

        public record StepView(int index, StepStatus status, String result) {
        }

        public String render() {
            StringBuilder text = new StringBuilder("工作流 ").append(id).append("（").append(name)
                    .append("）状态：").append(status().wireName())
                    .append("，完成 ").append(steps().stream().filter(step -> step.status() == StepStatus.COMPLETED)
                            .count()).append('/').append(steps().size()).append(" 步");
            if (lastError != null) {
                text.append("\n最近错误：").append(lastError);
            }
            for (Summary.StepView step : steps()) {
                text.append("\n").append(step.status().marker()).append(" 步骤 ").append(step.index())
                        .append(" [").append(step.status().wireName()).append(']');
                if (step.result() != null) {
                    text.append("：").append(step.result());
                }
            }
            if (status() == WorkflowStatus.FAILED || status() == WorkflowStatus.PAUSED) {
                text.append("\n可修复后再次调用 workflow_run 从未完成的步骤继续。");
            }
            return text.toString();
        }
    }
}
