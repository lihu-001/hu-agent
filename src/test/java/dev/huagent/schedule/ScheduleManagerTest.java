package dev.huagent.schedule;

import dev.huagent.background.BackgroundManager;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleManagerTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:30Z");
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    @TempDir
    Path root;
    private ScheduleStore store;
    private FakeTool tool;
    private BackgroundManager background;
    private ScheduleManager manager;
    private MutableClock clock;
    private final List<String> logs = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        store = new ScheduleStore(root.resolve(ScheduleStore.DIRECTORY_NAME));
        Files.createDirectories(store.directory());
        tool = new FakeTool();
        background = new BackgroundManager(tool, logs::add);
        clock = new MutableClock(NOW, UTC);
        manager = new ScheduleManager(store, background, Duration.ofSeconds(15), UTC, clock, logs::add);
    }

    @AfterEach
    void tearDown() {
        manager.close();
        tool.release();
        background.shutdown();
    }

    @Test
    void startupRepairsRunningAndSkipsMissedSlotsWithoutReplaying() throws Exception {
        ScheduleRecord created = store.create("修复", "* * * * *", "echo x", NOW.minusSeconds(300), UTC);
        store.save(created.withRunStarted(NOW.minusSeconds(240), NOW.minusSeconds(180)));

        ScheduleManager.StartupReport report = manager.prepare(NOW);

        assertEquals(1, report.total());
        assertEquals(1, report.enabled());
        assertEquals(1, report.repaired());
        assertEquals(4, report.skippedSlots());
        ScheduleRecord repaired = store.load(created.id());
        assertEquals(ScheduleRunStatus.CANCELLED, repaired.lastStatus());
        assertEquals(1, repaired.runCount());
        assertEquals(NOW, repaired.lastFinishedAt());
        assertTrue(repaired.lastOutput().contains("上次进程退出时仍在运行"));
        assertEquals(Instant.parse("2026-01-01T00:01:00Z"), repaired.nextRunAt());
        assertEquals(0, tool.calls.get());
    }

    @Test
    void startupAdvancesOverdueScheduleAndReportsEveryMissedSlot() throws Exception {
        Instant created = NOW.minus(Duration.ofHours(5));
        ScheduleRecord schedule = store.create("整点", "0 * * * *", "echo x", created, UTC);

        ScheduleManager.StartupReport report = manager.prepare(NOW);

        assertEquals(5, report.skippedSlots());
        ScheduleRecord updated = store.load(schedule.id());
        assertEquals(Instant.parse("2026-01-01T01:00:00Z"), updated.nextRunAt());
        assertEquals(0, updated.runCount());
        assertEquals(0, tool.calls.get());
        assertTrue(logs.stream().anyMatch(line -> line.contains("错过") && line.contains("不补跑")));
    }

    @Test
    void tickFiresDueScheduleOnceThenPersistsBackgroundResult() throws Exception {
        ScheduleRecord schedule = manager.create("每分钟", "* * * * *", "echo x");
        assertEquals(Instant.parse("2026-01-01T00:01:00Z"), schedule.nextRunAt());

        manager.tick(NOW);
        assertEquals(0, tool.calls.get());

        clock.set(Instant.parse("2026-01-01T00:01:05Z"));
        manager.tick(clock.instant());
        awaitCalls(1);
        ScheduleRecord finished = awaitStatus(schedule.id(), ScheduleRunStatus.COMPLETED);
        assertEquals(1, finished.runCount());
        assertEquals(Instant.parse("2026-01-01T00:02:00Z"), finished.nextRunAt());
        assertTrue(finished.lastOutput().startsWith("后台任务 bg_1 completed"), finished.lastOutput());
        assertTrue(finished.lastOutput().contains("ok"), finished.lastOutput());
        assertTrue(background.list().isEmpty(), "定时计划不出现在 background_check");
        assertTrue(background.takeNotifications().isEmpty(), "定时结果不进入对话通知队列");

        clock.set(Instant.parse("2026-01-01T00:02:01Z"));
        manager.tick(clock.instant());
        awaitStatus(schedule.id(), ScheduleRunStatus.COMPLETED, record -> record.runCount() == 2);
        assertEquals(2, tool.calls.get());
    }

    @Test
    void pausedScheduleDoesNotFireAndResumeRecalculatesFromNow() throws Exception {
        ScheduleRecord schedule = manager.create("每小时", "0 * * * *", "echo x");
        ScheduleRecord paused = manager.pause(schedule.id());
        assertFalse(paused.enabled());
        assertNull(paused.nextRunAt());

        clock.set(Instant.parse("2026-01-01T02:30:00Z"));
        manager.tick(clock.instant());
        assertEquals(0, tool.calls.get());

        ScheduleRecord resumed = manager.resume(schedule.id());
        assertTrue(resumed.enabled());
        assertEquals(Instant.parse("2026-01-01T03:00:00Z"), resumed.nextRunAt());
        assertEquals(0, resumed.runCount());
    }

    @Test
    void skipsSlotWhileThePreviousRunIsStillRunning() throws Exception {
        tool.block = true;
        ScheduleRecord schedule = manager.create("慢任务", "* * * * *", "echo slow");
        clock.set(Instant.parse("2026-01-01T00:01:00Z"));
        manager.tick(clock.instant());
        awaitCalls(1);
        assertEquals(ScheduleRunStatus.RUNNING, store.load(schedule.id()).lastStatus());

        clock.set(Instant.parse("2026-01-01T00:02:00Z"));
        manager.tick(clock.instant());
        assertEquals(1, tool.calls.get());
        ScheduleRecord skipped = store.load(schedule.id());
        assertEquals(1, skipped.runCount());
        assertEquals(Instant.parse("2026-01-01T00:03:00Z"), skipped.nextRunAt());
        assertEquals(ScheduleRunStatus.RUNNING, skipped.lastStatus());
        assertTrue(logs.stream().anyMatch(line -> line.contains("上一次仍在运行")));

        tool.release();
        ScheduleRecord finished = awaitStatus(schedule.id(), ScheduleRunStatus.COMPLETED);
        assertEquals(Instant.parse("2026-01-01T00:03:00Z"), finished.nextRunAt());
    }

    @Test
    void failedLaunchIsRecordedAsFailedAndKeepsTheNextSlot() throws Exception {
        tool.block = true;
        for (int index = 0; index < BackgroundManager.MAX_RUNNING; index++) {
            background.start("fill" + index);
        }
        ScheduleRecord schedule = manager.create("被占满", "* * * * *", "echo x");
        clock.set(Instant.parse("2026-01-01T00:01:00Z"));
        manager.tick(clock.instant());

        ScheduleRecord failed = store.load(schedule.id());
        assertEquals(ScheduleRunStatus.FAILED, failed.lastStatus());
        assertEquals(1, failed.runCount());
        assertTrue(failed.lastOutput().contains("启动失败"), failed.lastOutput());
        assertEquals(Instant.parse("2026-01-01T00:02:00Z"), failed.nextRunAt());
    }

    @Test
    void cannotDeleteRunningScheduleButCanDeleteFinishedOne() throws Exception {
        tool.block = true;
        ScheduleRecord schedule = manager.create("运行中", "* * * * *", "echo x");
        clock.set(Instant.parse("2026-01-01T00:01:00Z"));
        manager.tick(clock.instant());
        awaitCalls(1);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> manager.delete(schedule.id()))
                .getMessage().contains("正在运行"));

        tool.release();
        awaitStatus(schedule.id(), ScheduleRunStatus.COMPLETED);
        manager.delete(schedule.id());
        assertThrows(IllegalArgumentException.class, () -> manager.get(schedule.id()));
        assertTrue(manager.list(warning -> fail(warning)).isEmpty());
    }

    @Test
    void rejectsNonPositivePollIntervalAndInvalidZoneUse() {
        assertThrows(IllegalArgumentException.class, () -> new ScheduleManager(store, background,
                Duration.ZERO, UTC, clock, logs::add));
        assertThrows(IllegalArgumentException.class, () -> new ScheduleManager(store, background,
                Duration.ofSeconds(-1), UTC, clock, logs::add));
    }

    private void awaitCalls(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (tool.calls.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, tool.calls.get());
    }

    private ScheduleRecord awaitStatus(String id, ScheduleRunStatus status) throws Exception {
        return awaitStatus(id, status, record -> true);
    }

    private ScheduleRecord awaitStatus(String id, ScheduleRunStatus status, Predicate<ScheduleRecord> extra)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ScheduleRecord last = null;
        while (System.nanoTime() < deadline) {
            last = store.load(id);
            if (last.lastStatus() == status && extra.test(last)) {
                return last;
            }
            Thread.sleep(10);
        }
        fail("等待 " + status + " 超时，最后状态：" + last);
        return last;
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = new AtomicReference<>(instant);
            this.zone = zone;
        }

        void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant.get(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    static final class FakeTool implements AgentTool {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch gate = new CountDownLatch(1);
        volatile boolean block;
        volatile String result = "退出码：0\nok";

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("fake", "测试用假工具", JSON.parseObject("{\"type\":\"object\"}"));
        }

        @Override
        public String execute(String arguments) throws InterruptedException {
            calls.incrementAndGet();
            if (block) {
                gate.await(10, TimeUnit.SECONDS);
            }
            return result;
        }

        void release() {
            gate.countDown();
        }
    }
}
