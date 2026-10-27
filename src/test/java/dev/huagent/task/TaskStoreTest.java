package dev.huagent.task;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskStoreTest {
    @TempDir
    Path root;
    private TaskStore store;

    @BeforeEach
    void setUp() {
        store = new TaskStore(root.resolve(TaskStore.DIRECTORY_NAME));
    }

    @Test
    void missingDirectoryMeansNoTasks() throws IOException {
        assertEquals(List.of(), store.list(warning -> fail(warning)));
        assertFalse(Files.exists(store.directory()));
        assertEquals("没有任务。用 task_create 创建。", store.render(List.of()));
    }

    @Test
    void createWritesOneJsonFilePerTask() throws IOException {
        TaskRecord task = store.create("  建表  ", " 用户表与订单表 ");
        assertTrue(task.id().matches("task_[0-9a-f]{8}"), task.id());
        assertEquals("建表", task.subject());
        assertEquals("用户表与订单表", task.description());
        assertEquals(TaskStatus.PENDING, task.status());
        assertNull(task.owner());
        JSONObject json = JSON.parseObject(Files.readString(store.directory().resolve(task.id() + ".json")));
        assertEquals(List.of("id", "subject", "description", "status", "owner", "blockedBy"),
                new ArrayList<>(json.keySet()));
        assertTrue(json.containsKey("owner"), "未认领时也写出 owner: null");
        assertEquals(task, store.load(task.id()));
    }

    @Test
    void createValidatesSubjectAndDescription() {
        assertThrows(IllegalArgumentException.class, () -> store.create(" ", null));
        assertThrows(IllegalArgumentException.class, () -> store.create("a\nb", null));
        assertThrows(IllegalArgumentException.class, () -> store.create("x".repeat(201), null));
        assertThrows(IllegalArgumentException.class, () -> store.create("a", "x".repeat(4001)));
    }

    @Test
    void dependenciesAreValidatedAsAWholeAndDeduplicated() throws IOException {
        TaskRecord schema = store.create("schema", null);
        TaskRecord api = store.create("api", null);
        TaskRecord tests = store.create("tests", null);
        assertEquals(List.of(schema.id()), store.addDependencies(api.id(), List.of(schema.id(), schema.id())).blockedBy());
        assertEquals(List.of(schema.id()), store.addDependencies(api.id(), List.of(schema.id())).blockedBy(),
                "重复添加不产生重复边");
        store.addDependencies(tests.id(), List.of(api.id()));

        assertThrows(IllegalArgumentException.class, () -> store.addDependencies(api.id(), List.of(api.id())));
        IllegalArgumentException cycle = assertThrows(IllegalArgumentException.class,
                () -> store.addDependencies(schema.id(), List.of(tests.id())));
        assertTrue(cycle.getMessage().contains("环"), cycle.getMessage());
        // 一项不存在时整次修改不保存。
        assertThrows(IllegalArgumentException.class,
                () -> store.addDependencies(tests.id(), List.of(schema.id(), "task_00000000")));
        assertEquals(List.of(api.id()), store.load(tests.id()).blockedBy());
        assertThrows(IllegalArgumentException.class, () -> store.addDependencies(tests.id(), List.of("../x")));
    }

    @Test
    void claimRequiresCompletedDependenciesAndCompleteReportsUnblocked() throws IOException {
        TaskRecord schema = store.create("schema", null);
        TaskRecord api = store.create("api", null);
        TaskRecord docs = store.create("docs", null);
        TaskRecord tests = store.create("tests", null);
        store.addDependencies(api.id(), List.of(schema.id()));
        store.addDependencies(docs.id(), List.of(schema.id()));
        store.addDependencies(tests.id(), List.of(api.id(), schema.id()));

        IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
                () -> store.claim(api.id(), "agent"));
        assertTrue(blocked.getMessage().contains(schema.id()), blocked.getMessage());
        assertThrows(IllegalArgumentException.class, () -> store.complete(schema.id(), "agent"), "未认领不能完成");

        assertEquals(TaskStatus.IN_PROGRESS, store.claim(schema.id(), "agent").status());
        assertThrows(IllegalArgumentException.class, () -> store.claim(schema.id(), "agent"), "不能重复认领");
        assertThrows(IllegalArgumentException.class, () -> store.addDependencies(schema.id(), List.of(docs.id())),
                "认领后不能再改依赖");
        assertThrows(IllegalArgumentException.class, () -> store.complete(schema.id(), "other"), "只能由认领者完成");

        TaskStore.Completion done = store.complete(schema.id(), "agent");
        assertEquals(TaskStatus.COMPLETED, done.task().status());
        assertEquals(List.of(api.id(), docs.id()).stream().sorted().toList(),
                done.unblocked().stream().map(TaskRecord::id).sorted().toList(), "tests 仍依赖 api，不算解锁");

        store.claim(api.id(), "agent");
        assertEquals(List.of(tests.id()), store.complete(api.id(), "agent").unblocked().stream()
                .map(TaskRecord::id).toList());
    }

    @Test
    void stateSurvivesNewStoreInstance() throws IOException {
        TaskRecord a = store.create("a", null);
        TaskRecord b = store.create("b", null);
        store.addDependencies(b.id(), List.of(a.id()));
        store.claim(a.id(), "agent");

        TaskStore reopened = new TaskStore(store.directory());
        List<TaskRecord> tasks = reopened.list(warning -> fail(warning));
        assertEquals(2, tasks.size());
        String rendered = reopened.render(tasks);
        assertTrue(rendered.contains("[>] " + a.id() + "：a [in_progress] 认领者=agent"), rendered);
        assertTrue(rendered.contains("[ ] " + b.id() + "：b [pending] 依赖=" + a.id() + " 阻塞于=" + a.id()), rendered);
        reopened.complete(a.id(), "agent");
        assertTrue(reopened.render(reopened.list(w -> { })).contains(b.id() + "：b [pending] 依赖=" + a.id() + " 可开始"));
    }

    @Test
    void missingDependencyCountsAsIncomplete() throws IOException {
        TaskRecord a = store.create("a", null);
        TaskRecord b = store.create("b", null);
        store.addDependencies(b.id(), List.of(a.id()));
        Files.delete(store.directory().resolve(a.id() + ".json"));
        assertEquals(List.of(a.id()), store.incompleteDependencies(store.load(b.id())));
        assertThrows(IllegalArgumentException.class, () -> store.claim(b.id(), "agent"));
    }

    @Test
    void invalidFilesAreSkippedWithWarnings() throws IOException {
        TaskRecord ok = store.create("ok", null);
        Path dir = store.directory();
        Files.writeString(dir.resolve("task_11111111.json"), "不是 JSON");
        Files.writeString(dir.resolve("task_22222222.json"), "{\"id\":\"task_33333333\",\"subject\":\"x\","
                + "\"status\":\"pending\",\"owner\":null,\"blockedBy\":[]}");
        Files.writeString(dir.resolve("task_44444444.json"), "{\"id\":\"task_44444444\",\"subject\":\"x\","
                + "\"status\":\"in_progress\",\"owner\":null,\"blockedBy\":[]}");
        Files.writeString(dir.resolve("task_55555555.json"), "{\"id\":\"task_55555555\",\"subject\":\"x\","
                + "\"status\":\"pending\",\"owner\":null,\"blockedBy\":[],\"extra\":1}");
        Files.write(dir.resolve("task_66666666.json"), new byte[]{(byte) 0xff, (byte) 0xfe});
        Files.writeString(dir.resolve("notes.txt"), "忽略");
        List<String> warnings = new ArrayList<>();
        List<TaskRecord> tasks = store.list(warnings::add);
        assertEquals(List.of(ok.id()), tasks.stream().map(TaskRecord::id).toList());
        assertEquals(5, warnings.size(), warnings.toString());
        assertTrue(warnings.get(1).contains("id 必须与文件名一致"), warnings.toString());
        assertTrue(warnings.get(2).contains("认领者"), warnings.toString());
    }

    @Test
    void manualEditIsVisibleImmediately() throws IOException {
        TaskRecord task = store.create("a", null);
        Path file = store.directory().resolve(task.id() + ".json");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8).replace("\"a\"", "\"改名\""));
        assertEquals("改名", store.load(task.id()).subject());
    }

    @Test
    void writesLeaveNoTempFilesAndPathMustBeDirectory() throws IOException {
        TaskRecord task = store.create("a", null);
        store.claim(task.id(), "agent");
        store.complete(task.id(), "agent");
        try (var files = Files.list(store.directory())) {
            // S13：.lock 是认领用的文件锁，不是任务数据。
            assertEquals(List.of(task.id() + ".json"), files.map(p -> p.getFileName().toString())
                    .filter(name -> !name.equals(TaskStore.LOCK_FILE)).toList());
        }
        Path notDirectory = Files.writeString(root.resolve("file"), "x");
        assertThrows(IOException.class, () -> new TaskStore(notDirectory).list(w -> { }));
    }

    @Test
    void idValidationRejectsTraversal() {
        for (String id : List.of("task_1234567", "task_ABCDEF12", "../task_12345678", "task_12345678.json")) {
            assertThrows(IllegalArgumentException.class, () -> store.load(id), id);
        }
    }
}
