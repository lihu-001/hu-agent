package dev.huagent.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowStoreTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T00:10:00Z");

    @TempDir
    Path root;

    private WorkflowStore store() {
        return new WorkflowStore(root.resolve(WorkflowStore.DIRECTORY_NAME));
    }

    @Test
    void createPersistsPendingWorkflowAndRoundTrips() throws IOException {
        WorkflowStore store = store();
        WorkflowRecord created = store.create("站点检查", List.of("统计任务数", "读取结果并总结"), T0);
        assertTrue(created.id().matches("workflow_[0-9a-f]{8}"));
        assertEquals(WorkflowStatus.PENDING, created.status());
        assertEquals(2, created.steps().size());
        assertEquals(StepStatus.PENDING, created.steps().get(0).status());
        assertEquals(T0, created.createdAt());
        assertEquals(T0, created.updatedAt());
        assertNull(created.lastError());

        WorkflowRecord loaded = store.load(created.id());
        assertEquals(created, loaded);
        assertEquals(created.steps().get(0), loaded.steps().get(0));
        assertTrue(Files.readString(store.directory().resolve(created.id() + ".json"), StandardCharsets.UTF_8)
                .startsWith("{"));
    }

    @Test
    void createRejectsInvalidNameAndSteps() throws IOException {
        WorkflowStore store = store();
        assertThrows(IllegalArgumentException.class, () -> store.create(" ", List.of("a"), T0));
        assertThrows(IllegalArgumentException.class, () -> store.create("a\nb", List.of("a"), T0));
        assertThrows(IllegalArgumentException.class, () -> store.create("名".repeat(101), List.of("a"), T0));
        assertThrows(IllegalArgumentException.class, () -> store.create("名", List.of(), T0));
        assertThrows(IllegalArgumentException.class, () -> store.create("名", null, T0));
        assertThrows(IllegalArgumentException.class, () -> store.create("名", List.of("  "), T0));
        assertThrows(IllegalArgumentException.class,
                () -> store.create("名", List.of("长".repeat(4001)), T0));
        assertThrows(IllegalArgumentException.class,
                () -> store.create("名", new ArrayList<>(
                        java.util.Collections.nCopies(WorkflowStore.MAX_STEPS + 1, "一步")), T0));
        assertEquals(List.of(), store.list(w -> fail(w)), "校验失败不落盘");
        assertFalse(Files.exists(store.directory()), "校验失败不建目录");
    }

    @Test
    void exactlyMaxStepsIsAllowed() throws IOException {
        WorkflowStore store = store();
        WorkflowRecord full = store.create("满额", new ArrayList<>(
                java.util.Collections.nCopies(WorkflowStore.MAX_STEPS, "一步")), T0);
        assertEquals(WorkflowStore.MAX_STEPS, full.steps().size());
        assertEquals(WorkflowStore.MAX_STEPS, store.load(full.id()).steps().size());
    }

    @Test
    void idMustMatchWorkflowPattern() throws IOException {
        WorkflowStore store = store();
        assertThrows(IllegalArgumentException.class, () -> store.load("task_0a1b2c3d"));
        assertThrows(IllegalArgumentException.class, () -> store.load("../secret"));
        assertThrows(IllegalArgumentException.class, () -> store.load(null));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.load("workflow_00000000"));
        assertEquals("没有 ID 为 workflow_00000000 的工作流", e.getMessage());
    }

    @Test
    void listSkipsInvalidFilesWithWarning() throws IOException {
        WorkflowStore store = store();
        Path dir = Files.createDirectories(store.directory());
        WorkflowRecord good = store.create("好的", List.of("一步"), T0);
        Files.writeString(dir.resolve("workflow_11111111.json"), "坏文件");
        Files.writeString(dir.resolve("workflow_22222222.json"), "{}");
        // id 与文件名不一致
        Files.writeString(dir.resolve("workflow_33333333.json"), WorkflowStore.toJson(new WorkflowRecord(
                "workflow_44444444", "不一致", WorkflowStatus.PENDING,
                List.of(WorkflowStep.pending("一步")), T0, T0, null)));
        // 未知字段
        Files.writeString(dir.resolve("workflow_55555555.json"),
                "{\"id\":\"workflow_55555555\",\"name\":\"多字段\",\"status\":\"pending\",\"steps\":"
                        + "[{\"prompt\":\"一步\",\"status\":\"pending\"}],\"createdAt\":\"" + T0
                        + "\",\"updatedAt\":\"" + T0 + "\",\"lastError\":null,\"extra\":1}");
        // 超过 64 KiB
        StringBuilder big = new StringBuilder("{\"id\":\"workflow_66666666\",\"name\":\"大文件\",\"status\":\"pending\","
                + "\"steps\":[{\"prompt\":\"");
        big.append("长".repeat(40000));
        big.append("\",\"status\":\"pending\"}],\"createdAt\":\"" + T0 + "\",\"updatedAt\":\"" + T0
                + "\",\"lastError\":null}");
        Files.writeString(dir.resolve("workflow_66666666.json"), big.toString());
        // 不是 workflow_ 前缀的文件被忽略
        Files.writeString(dir.resolve("readme.txt"), "忽略");

        List<String> warnings = new ArrayList<>();
        List<WorkflowRecord> workflows = store.list(warnings::add);
        assertEquals(1, workflows.size());
        assertEquals(good.id(), workflows.get(0).id());
        assertEquals(5, warnings.size());
        assertTrue(warnings.get(0).contains("跳过 workflow_11111111.json"), warnings.toString());
    }

    @Test
    void saveReplacesAtomicallyAndLeavesNoTempFiles() throws IOException {
        WorkflowStore store = store();
        WorkflowRecord workflow = store.create("改进度", List.of("一步", "两步"), T0);
        List<WorkflowStep> steps = List.of(
                workflow.steps().get(0).markRunning(T0).finish(StepStatus.COMPLETED, "结果一", T1),
                workflow.steps().get(1));
        // 有步骤已开始时整体状态不再是 pending，与 parse 的组合校验一致。
        store.save(workflow.withSteps(steps, T1).withStatus(WorkflowStatus.PAUSED, T1));
        WorkflowRecord loaded = store.load(workflow.id());
        assertEquals(StepStatus.COMPLETED, loaded.steps().get(0).status());
        assertEquals("结果一", loaded.steps().get(0).result());
        assertEquals(T1, loaded.steps().get(0).finishedAt());
        try (var files = Files.list(store.directory())) {
            assertTrue(files.allMatch(file -> file.getFileName().toString().matches(
                    "workflow_[0-9a-f]{8}\\.json")), "不留临时文件");
        }
    }

    @Test
    void repairAtStartupFixesRunningWorkflowAndSteps() throws IOException {
        WorkflowStore store = store();
        WorkflowRecord workflow = store.create("中断恢复", List.of("一步", "两步"), T0);
        List<WorkflowStep> steps = List.of(
                workflow.steps().get(0).markRunning(T0).finish(StepStatus.COMPLETED, "结果一", T1),
                workflow.steps().get(1).markRunning(T1));
        store.save(new WorkflowRecord(workflow.id(), workflow.name(), WorkflowStatus.RUNNING, steps, T0, T1, null));

        List<String> warnings = new ArrayList<>();
        int repaired = store.repairAtStartup(T1, warnings::add);
        assertEquals(1, repaired);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("已修复为 paused"), warnings.toString());
        WorkflowRecord fixed = store.load(workflow.id());
        assertEquals(WorkflowStatus.PAUSED, fixed.status());
        assertEquals(StepStatus.COMPLETED, fixed.steps().get(0).status());
        assertEquals(StepStatus.PENDING, fixed.steps().get(1).status());
        assertNull(fixed.steps().get(1).startedAt());

        // 非 running 的工作流不动；再次修复为 0。
        assertEquals(0, store.repairAtStartup(T1, warnings::add));
    }

    @Test
    void parseRejectsInconsistentStatusCombinations() throws IOException {
        WorkflowStore store = store();
        Path dir = Files.createDirectories(store.directory());
        // completed 工作流带未完成步骤
        write(dir, "workflow_10000001", "completed",
                List.of(WorkflowStep.pending("一步")), null);
        // failed 工作流没有 lastError
        write(dir, "workflow_10000002", "failed",
                List.of(WorkflowStep.pending("一步").markRunning(T0).finish(StepStatus.FAILED, null, T1)), null);
        // completed 步骤缺 result
        write(dir, "workflow_10000003", "paused",
                List.of(WorkflowStep.pending("一步").markRunning(T0).finish(StepStatus.COMPLETED, null, T1)), null);
        // pending 工作流带已开始步骤
        write(dir, "workflow_10000004", "pending",
                List.of(WorkflowStep.pending("一步").markRunning(T0)), null);
        // pending 步骤带 result
        write(dir, "workflow_10000005", "paused",
                List.of(new WorkflowStep("一步", StepStatus.PENDING, "结果", null, null)), null);
        // 结束步骤缺 finishedAt
        write(dir, "workflow_10000006", "paused",
                List.of(new WorkflowStep("一步", StepStatus.COMPLETED, "结果", T0, null)), null);

        for (String id : List.of("workflow_10000001", "workflow_10000002", "workflow_10000003",
                "workflow_10000004", "workflow_10000005", "workflow_10000006")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> store.load(id), id);
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void maxWorkflowsIsEnforced() throws IOException {
        WorkflowStore store = store();
        for (int i = 0; i < WorkflowStore.MAX_WORKFLOWS; i++) {
            store.create("工作流" + i, List.of("一步"), T0);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.create("超出", List.of("一步"), T0));
        assertTrue(e.getMessage().contains("上限"));
    }

    @Test
    void renderListsStatusProgressAndError() throws IOException {
        WorkflowStore store = store();
        assertEquals("没有工作流。用 workflow_create 创建。", WorkflowStore.render(List.of()));
        WorkflowRecord failed = store.create("失败流", List.of("一步", "两步"), T0);
        List<WorkflowStep> steps = List.of(
                failed.steps().get(0).markRunning(T0).finish(StepStatus.COMPLETED, "结果一", T1),
                failed.steps().get(1).markRunning(T1).finish(StepStatus.FAILED, "未完成说明", T1));
        store.save(new WorkflowRecord(failed.id(), failed.name(), WorkflowStatus.FAILED, steps, T0, T1, "步骤 2 失败"));
        String text = WorkflowStore.render(store.list(w -> fail(w)));
        assertTrue(text.contains("[!] " + failed.id() + "：失败流"), text);
        assertTrue(text.contains("状态=failed"), text);
        assertTrue(text.contains("进度=1/2"), text);
        assertTrue(text.contains("最近错误=步骤 2 失败"), text);
    }

    private static void write(Path dir, String id, String status, List<WorkflowStep> steps, String lastError)
            throws IOException {
        WorkflowRecord record = new WorkflowRecord(id, "测试", WorkflowStatus.parse(status), steps, T0, T0, lastError);
        Files.writeString(dir.resolve(id + ".json"), WorkflowStore.toJson(record));
    }
}
