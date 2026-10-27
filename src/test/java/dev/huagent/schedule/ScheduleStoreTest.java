package dev.huagent.schedule;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleStoreTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:30Z");
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    @TempDir
    Path root;
    private ScheduleStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new ScheduleStore(root.resolve(ScheduleStore.DIRECTORY_NAME));
        Files.createDirectories(store.directory());
    }

    @Test
    void createsPersistentEnabledScheduleAndReloadsIt() throws IOException {
        ScheduleRecord created = store.create("早间构建", "*/5 * * * *", "mvn -q test", NOW, UTC);
        assertEquals("schedule_" + created.id().substring("schedule_".length()), created.id());
        assertTrue(created.id().matches("schedule_[0-9a-f]{8}"));
        assertTrue(created.enabled());
        assertEquals(NOW, created.createdAt());
        assertEquals(Instant.parse("2026-01-01T00:05:00Z"), created.nextRunAt());
        assertEquals(0, created.runCount());

        Path file = store.directory().resolve(created.id() + ".json");
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"lastStatus\":null"), json);
        assertTrue(json.contains("\"nextRunAt\":\"2026-01-01T00:05:00Z\""), json);

        ScheduleRecord loaded = store.load(created.id());
        assertEquals(created, loaded);
        assertEquals(List.of(created), store.list(warning -> fail(warning)));
    }

    @Test
    void listSortsByIdAndReportsInvalidFilesWithoutStopping() throws IOException {
        ScheduleRecord second = store.create("b", "0 * * * *", "echo b", NOW, UTC);
        ScheduleRecord first = store.create("a", "0 * * * *", "echo a", NOW, UTC);
        String firstId = first.id().compareTo(second.id()) < 0 ? first.id() : second.id();
        String secondId = first.id().compareTo(second.id()) < 0 ? second.id() : first.id();
        Files.writeString(store.directory().resolve("schedule_deadbeef.json"), "{not json",
                StandardCharsets.UTF_8);
        Files.writeString(store.directory().resolve("schedule_cafebabe.json"),
                "{\"id\":\"schedule_00000000\",\"name\":\"x\",\"cron\":\"* * * * *\",\"command\":\"x\","
                        + "\"enabled\":true,\"createdAt\":\"2026-01-01T00:00:00Z\","
                        + "\"nextRunAt\":\"2026-02-01T00:00:00Z\",\"lastRunAt\":null,"
                        + "\"lastFinishedAt\":null,\"lastStatus\":null,\"lastOutput\":null,\"runCount\":0}",
                StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();
        List<ScheduleRecord> schedules = store.list(warnings::add);
        assertEquals(List.of(firstId, secondId), schedules.stream().map(ScheduleRecord::id).toList());
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("不能包含") || warnings.get(0).contains("id 必须与文件名一致"), warnings.get(0));
    }

    @Test
    void invalidStateCombinationsAreRejectedOnRead() throws IOException {
        assertSkipped(raw("schedule_00000001", "true", null, null, null, 0),
                "启用的计划必须有 nextRunAt");
        assertSkipped(raw("schedule_00000002", "false", "2026-02-01T00:00:00Z", null, null, 0),
                "暂停的计划不能有 nextRunAt");
        assertSkipped(raw("schedule_00000003", "true", "2026-02-01T00:00:00Z", null, null, 1),
                "没有运行记录时 runCount 必须为 0");
        assertSkipped(raw("schedule_00000004", "true", "2026-02-01T00:00:00Z", null, "running", 1),
                "running 状态必须有 lastRunAt");
    }

    @Test
    void oversizedAndNonRegularFilesAreSkipped() throws IOException {
        Files.writeString(store.directory().resolve("schedule_0000000a.json"), "x".repeat(70 * 1024),
                StandardCharsets.UTF_8);
        Files.createDirectories(store.directory().resolve("schedule_0000000b.json"));
        List<String> warnings = new ArrayList<>();
        assertTrue(store.list(warnings::add).isEmpty());
        assertEquals(2, warnings.size());
    }

    @Test
    void pauseResumeAndRunLifecyclePersist() throws IOException {
        ScheduleRecord created = store.create("每小时", "0 * * * *", "echo hi", NOW, UTC);
        ScheduleRecord paused = created.paused();
        store.save(paused);
        assertFalse(store.load(created.id()).enabled());
        assertNull(store.load(created.id()).nextRunAt());

        Instant next = Instant.parse("2026-01-01T02:00:00Z");
        store.save(paused.resumed(next));
        assertEquals(next, store.load(created.id()).nextRunAt());

        Instant startedAt = Instant.parse("2026-01-01T02:00:01Z");
        Instant following = Instant.parse("2026-01-01T03:00:00Z");
        ScheduleRecord running = store.load(created.id()).withRunStarted(startedAt, following);
        store.save(running);
        ScheduleRecord loadedRunning = store.load(created.id());
        assertEquals(ScheduleRunStatus.RUNNING, loadedRunning.lastStatus());
        assertEquals(1, loadedRunning.runCount());
        assertNull(loadedRunning.lastFinishedAt());

        store.save(loadedRunning.withRunFinished(ScheduleRunStatus.COMPLETED,
                Instant.parse("2026-01-01T02:00:02Z"), "退出码：0\nok"));
        ScheduleRecord finished = store.load(created.id());
        assertEquals(ScheduleRunStatus.COMPLETED, finished.lastStatus());
        assertEquals(following, finished.nextRunAt());
        assertEquals("退出码：0\nok", finished.lastOutput());
    }

    @Test
    void deleteRemovesFileAndMissingIdsAreRejected() throws IOException {
        ScheduleRecord created = store.create("临时", "0 * * * *", "echo x", NOW, UTC);
        assertTrue(Files.exists(store.directory().resolve(created.id() + ".json")));
        store.delete(created.id());
        assertFalse(Files.exists(store.directory().resolve(created.id() + ".json")));
        assertThrows(IllegalArgumentException.class, () -> store.load(created.id()));
        assertThrows(IllegalArgumentException.class, () -> store.delete(created.id()));
        assertThrows(IllegalArgumentException.class, () -> store.load("schedule_XYZ"));
    }

    @Test
    void validatesNameCronAndCommand() {
        assertThrows(IllegalArgumentException.class, () -> store.create(" ", "* * * * *", "x", NOW, UTC));
        assertThrows(IllegalArgumentException.class, () -> store.create("多\n行", "* * * * *", "x", NOW, UTC));
        assertThrows(IllegalArgumentException.class,
                () -> store.create("x".repeat(ScheduleStore.MAX_NAME_CHARS + 1), "* * * * *", "x", NOW, UTC));
        assertThrows(IllegalArgumentException.class, () -> store.create("x", "bad", "x", NOW, UTC));
        assertThrows(IllegalArgumentException.class, () -> store.create("x", "* * * * *", " ", NOW, UTC));
        assertThrows(IllegalArgumentException.class,
                () -> store.create("x", "* * * * *", "c".repeat(ScheduleStore.MAX_COMMAND_CHARS + 1), NOW, UTC));
    }

    @Test
    void enforcesScheduleLimit() throws IOException {
        for (int index = 0; index < ScheduleStore.MAX_SCHEDULES; index++) {
            store.create("计划" + index, "0 * * * *", "echo x", NOW, UTC);
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> store.create("多一个", "0 * * * *", "echo x", NOW, UTC));
        assertTrue(error.getMessage().contains("上限"));
    }

    @Test
    void renderShowsStateAndTruncatesNothing() throws IOException {
        ScheduleRecord created = store.create("日报", "30 8 * * *", "echo report", NOW, UTC);
        assertTrue(ScheduleStore.render(List.of(), UTC).contains("没有定时计划"));
        String line = ScheduleStore.render(List.of(created), UTC);
        assertTrue(line.contains("[on] " + created.id() + "：日报"), line);
        assertTrue(line.contains("cron=30 8 * * *"), line);
        assertTrue(line.contains("下次=2026-01-01 08:30"), line);
        assertTrue(line.contains("触发=0 次"), line);
        String paused = ScheduleStore.render(List.of(created.paused()), UTC);
        assertTrue(paused.contains("[off]") && paused.contains("已暂停"), paused);
    }

    private String raw(String id, String enabled, String nextRunAt, String lastRunAt,
                       String lastStatus, int runCount) {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", "手动");
        json.put("cron", "* * * * *");
        json.put("command", "echo x");
        json.put("enabled", Boolean.parseBoolean(enabled));
        json.put("createdAt", "2026-01-01T00:00:00Z");
        json.put("nextRunAt", nextRunAt);
        json.put("lastRunAt", lastRunAt);
        json.put("lastFinishedAt", null);
        json.put("lastStatus", lastStatus);
        json.put("lastOutput", null);
        json.put("runCount", runCount);
        return JSON.toJSONString(json);
    }

    private void assertSkipped(String content, String expectedMessage) throws IOException {
        String id = JSON.parseObject(content).getString("id");
        try (var files = Files.list(store.directory())) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        Files.writeString(store.directory().resolve(id + ".json"), content, StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();
        assertTrue(store.list(warnings::add).isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(expectedMessage), warnings.get(0));
    }
}
