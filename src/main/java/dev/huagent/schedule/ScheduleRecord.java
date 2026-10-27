package dev.huagent.schedule;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条持久化的定时计划。nextRunAt 在启用时必须有值、暂停时为 null；lastStatus 等字段描述最近一次
 * 到点触发：运行中为 running，结束后记录真实结果。时间统一保存为 Instant（UTC），cron 按运行时区解释。
 */
public record ScheduleRecord(String id, String name, String cron, String command, boolean enabled,
                             Instant createdAt, Instant nextRunAt, Instant lastRunAt, Instant lastFinishedAt,
                             ScheduleRunStatus lastStatus, String lastOutput, int runCount) {
    public ScheduleRecord {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(cron);
        Objects.requireNonNull(command);
        Objects.requireNonNull(createdAt);
        runCount = Math.max(runCount, 0);
    }

    /** 启动时把过期的下一次时间前移，不改动上次运行记录。 */
    ScheduleRecord withNextRun(Instant next) {
        return new ScheduleRecord(id, name, cron, command, enabled, createdAt, next, lastRunAt,
                lastFinishedAt, lastStatus, lastOutput, runCount);
    }

    ScheduleRecord paused() {
        return new ScheduleRecord(id, name, cron, command, false, createdAt, null, lastRunAt,
                lastFinishedAt, lastStatus, lastOutput, runCount);
    }

    ScheduleRecord resumed(Instant next) {
        return new ScheduleRecord(id, name, cron, command, true, createdAt, next, lastRunAt,
                lastFinishedAt, lastStatus, lastOutput, runCount);
    }

    /** 到点并已交给后台执行：次数加一，下一次时间立即前移，避免同一时点重复触发。 */
    ScheduleRecord withRunStarted(Instant now, Instant next) {
        return new ScheduleRecord(id, name, cron, command, enabled, createdAt, next, now,
                null, ScheduleRunStatus.RUNNING, null, runCount + 1);
    }

    /** 运行结束、启动失败或进程退出后的状态修正。 */
    ScheduleRecord withRunFinished(ScheduleRunStatus status, Instant now, String output) {
        return new ScheduleRecord(id, name, cron, command, enabled, createdAt, nextRunAt, lastRunAt,
                now, status, output, runCount);
    }
}
