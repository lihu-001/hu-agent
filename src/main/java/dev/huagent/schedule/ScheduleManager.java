package dev.huagent.schedule;

import dev.huagent.background.BackgroundCompletion;
import dev.huagent.background.BackgroundManager;
import dev.huagent.background.BackgroundStatus;
import dev.huagent.background.BackgroundTask;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 定时计划调度器：守护线程按 pollInterval 检查 .schedules，到点后交给 BackgroundManager 执行。
 * 计划文件是唯一状态源；上一次运行未结束时跳过到点时点，不并发执行同一个计划。
 * 进程重启时不补跑错过时点，只把下一次时间前移并报告；上次退出时仍在运行的记录修正为取消。
 * 运行结果写回计划文件，用 schedule_get / schedule_list 查看。
 * S15：非取消的终态还会送达 scheduledEvents（对话事件中心），为 null 时与 S12 相同。
 */
public final class ScheduleManager {
    /** 启动时最多逐个数到这么多个错过时点，避免分钟级计划停机很久时循环过久。 */
    static final long MAX_REPORTED_MISSED = 10_000;

    /** 启动修复与跳过统计。total 含无效文件以外的全部计划；enabled 是修复后的启用数。 */
    public record StartupReport(int total, int enabled, int repaired, long skippedSlots) {
    }

    private final ScheduleStore store;
    private final BackgroundManager background;
    private final Duration pollInterval;
    private final ZoneId zone;
    private final Clock clock;
    private final Consumer<String> log;
    /** S15：终态事件的出口（事件中心），null 时结果只写回计划文件。 */
    private final Consumer<String> scheduledEvents;

    private ScheduledExecutorService executor;
    private boolean started;
    private boolean closed;
    private String lastTickError;

    public ScheduleManager(ScheduleStore store, BackgroundManager background, Duration pollInterval,
                           ZoneId zone, Clock clock, Consumer<String> log) {
        this(store, background, pollInterval, zone, clock, log, null);
    }

    /** S15：scheduledEvents 接收非取消终态的一行摘要与截断输出，事件中心再交给模型。 */
    public ScheduleManager(ScheduleStore store, BackgroundManager background, Duration pollInterval,
                           ZoneId zone, Clock clock, Consumer<String> log, Consumer<String> scheduledEvents) {
        this.store = Objects.requireNonNull(store);
        this.background = Objects.requireNonNull(background);
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("scheduler.poll-seconds 必须为正整数");
        }
        this.pollInterval = pollInterval;
        this.zone = Objects.requireNonNull(zone);
        this.clock = Objects.requireNonNull(clock);
        this.log = Objects.requireNonNull(log);
        this.scheduledEvents = scheduledEvents;
        background.addCompletionListener(this::completed);
        // 会话结束时先停调度，再由 BackgroundManager 取消仍在运行的命令。
        background.addShutdownListener(this::close);
    }

    /**
     * 修复上次退出的残留状态、跳过错过时点，然后启动守护调度线程；重复调用无副作用。
     * debug 为 false 时（hu 默认）不打印加载统计（可用 /schedules 查看），修复与错过时点的说明照常打印。
     */
    public synchronized void start(boolean debug) throws IOException {
        if (started) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("定时调度器已关闭。");
        }
        StartupReport report = prepare(clock.instant());
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "schedule-ticker");
            thread.setDaemon(true);
            return thread;
        });
        long millis = pollInterval.toMillis();
        scheduler.scheduleWithFixedDelay(this::tickQuietly, millis, millis, TimeUnit.MILLISECONDS);
        executor = scheduler;
        started = true;
        if (debug) {
            log.accept("计划> 已加载 " + report.total() + " 个计划，启用 " + report.enabled() + " 个；每 "
                    + pollInterval.toSeconds() + " 秒检查一次，结果写入 " + store.directory());
        }
    }

    /** 启动修复：running 修正为 cancelled；启用且已过期的计划计算下一次并报告错过的时点，不补跑。 */
    public synchronized StartupReport prepare(Instant now) throws IOException {
        Objects.requireNonNull(now);
        List<ScheduleRecord> schedules = store.list(log);
        int enabled = 0;
        int repaired = 0;
        long skipped = 0;
        for (ScheduleRecord loaded : schedules) {
            ScheduleRecord updated = loaded;
            if (updated.lastStatus() == ScheduleRunStatus.RUNNING) {
                updated = updated.withRunFinished(ScheduleRunStatus.CANCELLED, now,
                        "上次进程退出时仍在运行，无法确认结果，已标记为取消。");
                repaired++;
                log.accept("计划> " + updated.id() + "（" + updated.name()
                        + "）上次退出时仍在运行，已标记为 cancelled。");
            }
            if (updated.enabled()) {
                enabled++;
                if (!updated.nextRunAt().isAfter(now)) {
                    CronExpression cron = CronExpression.parse(updated.cron());
                    skipped += missedSlots(cron, updated.nextRunAt(), now);
                    updated = skipMissed(updated, cron, now, "启动时已错过", "，不补跑");
                }
            }
            if (!updated.equals(loaded)) {
                saveQuietly(updated, "修正启动状态");
            }
        }
        return new StartupReport(schedules.size(), enabled, repaired, skipped);
    }

    /** 检查到点的计划；上一次仍在运行时跳过本时点，绝不并发执行同一条计划。 */
    public synchronized void tick(Instant now) throws IOException {
        Objects.requireNonNull(now);
        if (closed) {
            return;
        }
        // list 本身就是本次检查对计划文件的重读，无需逐个再 load。
        for (ScheduleRecord schedule : store.list(log)) {
            if (!schedule.enabled() || schedule.nextRunAt().isAfter(now)) {
                continue;
            }
            CronExpression cron = CronExpression.parse(schedule.cron());
            if (schedule.lastStatus() == ScheduleRunStatus.RUNNING) {
                store.save(skipMissed(schedule, cron, now, "上一次仍在运行，跳过", ""));
                continue;
            }
            fire(schedule, cron, now);
        }
    }

    /** 不补跑：把下一次时间前移到 now 之后，并报告跳过了多少个时点。 */
    private ScheduleRecord skipMissed(ScheduleRecord schedule, CronExpression cron, Instant now,
                                      String reason, String suffix) {
        long missed = missedSlots(cron, schedule.nextRunAt(), now);
        Instant next = cron.next(now, zone);
        log.accept("计划> " + schedule.id() + "（" + schedule.name() + "）" + reason + " "
                + missedText(missed) + suffix + "；下一次 " + ScheduleStore.time(next, zone));
        return schedule.withNextRun(next);
    }

    private void fire(ScheduleRecord schedule, CronExpression cron, Instant now) {
        Instant next = cron.next(now, zone);
        ScheduleRecord running = schedule.withRunStarted(now, next);
        try {
            // 先落盘再启动：进程若在两步之间退出，下次启动会把残留的 running 修正为 cancelled。
            store.save(running);
        } catch (IOException e) {
            log.accept("计划> " + schedule.id() + " 无法记录运行状态，本次不执行（"
                    + e.getClass().getSimpleName() + "）。");
            return;
        }
        BackgroundTask task;
        try {
            task = background.start(schedule.command(), schedule.id());
        } catch (RuntimeException e) {
            String output = "启动失败：" + e.getMessage();
            saveQuietly(running.withRunFinished(ScheduleRunStatus.FAILED, clock.instant(), truncate(output)),
                    "记录启动失败");
            log.accept("计划> " + schedule.id() + "（" + schedule.name() + "）启动失败：" + e.getMessage());
            return;
        }
        log.accept("计划> " + schedule.id() + "（" + schedule.name() + "）已触发 " + task.id()
                + "，下一次 " + ScheduleStore.time(next, zone));
    }

    private void completed(BackgroundCompletion completion) {
        if (completion.source() == null) {
            return;
        }
        synchronized (this) {
            ScheduleRecord schedule;
            try {
                schedule = store.load(completion.source());
            } catch (IllegalArgumentException | IOException e) {
                // 计划可能已被删除；结果无处可写，静默丢弃即可。
                return;
            }
            if (schedule.lastStatus() != ScheduleRunStatus.RUNNING) {
                // 启动修正或外部编辑已经把状态推进到终态，不能用迟到的回调覆盖。
                return;
            }
            ScheduleRunStatus status = runStatus(completion.status());
            String output = truncate("后台任务 " + completion.id() + " " + completion.status().label()
                    + "，耗时 " + completion.elapsedMillis() + " ms" + outputSuffix(completion.result()));
            try {
                store.save(schedule.withRunFinished(status, clock.instant(), output));
            } catch (IOException e) {
                log.accept("计划> " + schedule.id() + " 无法保存运行结果（"
                        + e.getClass().getSimpleName() + "）。");
                return;
            }
            log.accept("计划> " + schedule.id() + "（" + schedule.name() + "）运行结束："
                    + status.wireName() + "，耗时 " + completion.elapsedMillis() + " ms；用 schedule_get 查看输出");
            // S15：取消只在会话结束时发生，不需要送达对话；其余终态交给事件中心。
            if (scheduledEvents != null && status != ScheduleRunStatus.CANCELLED) {
                scheduledEvents.accept("[" + schedule.id() + "（" + schedule.name() + "）] "
                        + status.wireName() + "，耗时 " + completion.elapsedMillis() + " ms\n" + output.strip());
            }
        }
    }

    public synchronized ScheduleRecord create(String name, String cron, String command) throws IOException {
        return store.create(name, cron, command, clock.instant(), zone);
    }

    public ZoneId zone() {
        return zone;
    }

    public synchronized List<ScheduleRecord> list(Consumer<String> warnings) throws IOException {
        return store.list(warnings);
    }

    public synchronized ScheduleRecord get(String id) throws IOException {
        return store.load(id);
    }

    /** 暂停后不再触发；已经在运行的命令继续跑完，结果仍写回这条计划。 */
    public synchronized ScheduleRecord pause(String id) throws IOException {
        ScheduleRecord schedule = store.load(id);
        if (!schedule.enabled()) {
            return schedule;
        }
        ScheduleRecord paused = schedule.paused();
        store.save(paused);
        log.accept("计划> " + id + "（" + schedule.name() + "）已暂停。");
        return paused;
    }

    /** 恢复时从当前时间重新计算下一次，不补跑暂停期间错过的时点。 */
    public synchronized ScheduleRecord resume(String id) throws IOException {
        ScheduleRecord schedule = store.load(id);
        if (schedule.enabled()) {
            return schedule;
        }
        Instant next = CronExpression.parse(schedule.cron()).next(clock.instant(), zone);
        ScheduleRecord resumed = schedule.resumed(next);
        store.save(resumed);
        log.accept("计划> " + id + "（" + schedule.name() + "）已恢复，下一次 "
                + ScheduleStore.time(next, zone));
        return resumed;
    }

    /** 正在运行的记录不删除，否则迟到的完成回调无处可写，用户也看不到结果。 */
    public synchronized void delete(String id) throws IOException {
        ScheduleRecord schedule = store.load(id);
        if (schedule.lastStatus() == ScheduleRunStatus.RUNNING) {
            throw new IllegalArgumentException("计划正在运行，等本次结束或先 schedule_pause 再删除。");
        }
        store.delete(id);
        log.accept("计划> " + id + "（" + schedule.name() + "）已删除。");
    }

    /** 停止调度线程；由 BackgroundManager.shutdown 在取消运行中命令之前调用。 */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void tickQuietly() {
        try {
            tick(clock.instant());
            lastTickError = null;
        } catch (IOException | RuntimeException e) {
            String message = "计划> 调度检查失败（" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()) + "）。";
            if (!message.equals(lastTickError)) {
                lastTickError = message;
                log.accept(message);
            }
        }
    }

    private void saveQuietly(ScheduleRecord schedule, String action) {
        try {
            store.save(schedule);
        } catch (IOException e) {
            log.accept("计划> " + action + "失败（" + e.getClass().getSimpleName() + "）：" + schedule.id());
        }
    }

    private static ScheduleRunStatus runStatus(BackgroundStatus status) {
        return switch (status) {
            case COMPLETED -> ScheduleRunStatus.COMPLETED;
            case FAILED -> ScheduleRunStatus.FAILED;
            case TIMED_OUT -> ScheduleRunStatus.TIMED_OUT;
            case CANCELLED, RUNNING -> ScheduleRunStatus.CANCELLED;
        };
    }

    /** 统计 [first, now] 内有多少个本应触发的时点；用于报告跳过，不用于补跑。 */
    private long missedSlots(CronExpression cron, Instant first, Instant now) {
        long count = 0;
        Instant cursor = first;
        while (!cursor.isAfter(now) && count < MAX_REPORTED_MISSED) {
            count++;
            cursor = cron.next(cursor, zone);
        }
        return count;
    }

    private static String missedText(long missed) {
        return (missed >= MAX_REPORTED_MISSED ? "至少 " + MAX_REPORTED_MISSED : Long.toString(missed)) + " 个时点";
    }

    private static String outputSuffix(String result) {
        String text = result.strip();
        return text.isEmpty() ? "" : "\n" + text;
    }

    static String truncate(String text) {
        return text.length() <= ScheduleStore.MAX_OUTPUT_CHARS ? text
                : text.substring(0, ScheduleStore.MAX_OUTPUT_CHARS) + "…（已截断）";
    }
}
