package dev.huagent.workflow;

import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.subagent.SubagentRunner;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRunnerTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path root;
    private final StringWriter buffer = new StringWriter();

    private WorkflowStore store() {
        return new WorkflowStore(root.resolve(WorkflowStore.DIRECTORY_NAME));
    }

    private WorkflowRunner runner(WorkflowStore store, ModelClient client) {
        buffer.getBuffer().setLength(0);
        SubagentRunner sub = new SubagentRunner(client, new ToolRegistry(List.of()), 6, "子代理提示词",
                new PrintWriter(buffer));
        return new WorkflowRunner(store, sub, java.time.Clock.systemDefaultZone(), new PrintWriter(buffer));
    }

    /** 记录每次子代理请求的 user 消息，可在回调里当场断言检查点状态。 */
    private interface Script {
        ChatMessage answer(int requestIndex, String userPrompt, WorkflowStore store) throws Exception;
    }

    private ModelClient model(WorkflowStore store, List<String> prompts, Script script) {
        return new ModelClient() {
            int next;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools)
                    throws IOException, InterruptedException {
                assertEquals(2, messages.size(), "子代理历史只有系统提示词和任务");
                String prompt = messages.get(1).content();
                prompts.add(prompt);
                try {
                    return script.answer(++next, prompt, store);
                } catch (IOException | InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    @Test
    void runsAllStepsInOrderAndCheckpointsAfterEach() throws Exception {
        WorkflowStore store = store();
        WorkflowRecord created = store.create("两步流", List.of("第一步任务", "第二步任务"), T0);
        List<String> prompts = new ArrayList<>();
        List<String> diskStatusAtRequest = new ArrayList<>();
        WorkflowRunner runner = runner(store, model(store, prompts, (index, prompt, current) -> {
            // 第二次请求到达时，第一步的结果必须已经落盘——这就是检查点。
            try {
                WorkflowRecord onDisk = current.load(created.id());
                diskStatusAtRequest.add(onDisk.steps().get(0).status().wireName());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return new ChatMessage("assistant", "步骤 " + index + " 的结果");
        }));
        WorkflowRunner.Summary summary = runner.run(created.id());
        assertEquals(WorkflowStatus.COMPLETED, summary.status());
        assertEquals(2, prompts.size(), "两次子代理请求");
        assertEquals("第一步任务", firstLine(prompts.get(0)));
        assertEquals("第二步任务", firstLine(prompts.get(1)));
        assertEquals(List.of("running", "completed"), diskStatusAtRequest,
                "第一次请求时步骤已标记 running；第二步请求时第一步已落盘");
        WorkflowRecord done = store.load(created.id());
        assertEquals(WorkflowStatus.COMPLETED, done.status());
        assertEquals("步骤 1 的结果", done.steps().get(0).result());
        assertEquals("步骤 2 的结果", done.steps().get(1).result());
        assertNotNull(done.steps().get(0).startedAt());
        assertTrue(buffer.toString().contains("工作流> 步骤 1/2 开始执行"), buffer.toString());
        assertTrue(summary.render().contains("状态：completed"), summary.render());
        assertTrue(summary.render().contains("[x] 步骤 1 [completed]：步骤 1 的结果"), summary.render());
    }

    @Test
    void passesPriorResultsToLaterSteps() throws Exception {
        WorkflowStore store = store();
        WorkflowRecord created = store.create("接力", List.of("产出数字", "使用上面的数字"), T0);
        List<String> prompts = new ArrayList<>();
        WorkflowRunner runner = runner(store, model(store, prompts, (index, prompt, current) ->
                new ChatMessage("assistant", index == 1 ? "答案是 42" : "收到")));
        runner.run(created.id());
        assertTrue(prompts.get(1).startsWith("使用上面的数字"), prompts.get(1));
        assertTrue(prompts.get(1).contains("<前序步骤结果>"), prompts.get(1));
        assertTrue(prompts.get(1).contains("步骤 1 的结果：\n答案是 42"), prompts.get(1));
        assertFalse(prompts.get(0).contains("前序步骤结果"), prompts.get(0));
    }

    @Test
    void priorResultsAreTruncatedWhenPassedOn() throws Exception {
        WorkflowStore store = store();
        WorkflowRecord created = store.create("长结果", List.of("产出长文本", "继续"), T0);
        List<String> prompts = new ArrayList<>();
        WorkflowRunner runner = runner(store, model(store, prompts, (index, prompt, current) ->
                new ChatMessage("assistant", "长".repeat(3000))));
        runner.run(created.id());
        // 存储保留最多 12000 字符，传给下一步截断到 2000。
        assertEquals(3000, store.load(created.id()).steps().get(0).result().length());
        assertTrue(prompts.get(1).contains("…（已截断）"), prompts.get(1));
        assertFalse(prompts.get(1).contains("长".repeat(2500)), "传递结果不超过 2000 字符");
    }

    @Test
    void modelFailureMarksStepFailedAndWorkflowFailed() throws Exception {
        WorkflowStore store = store();
        WorkflowRecord created = store.create("会失败", List.of("一步", "两步", "三步"), T0);
        List<String> prompts = new ArrayList<>();
        WorkflowRunner runner = runner(store, model(store, prompts, (index, prompt, current) -> {
            if (index == 2) {
                throw new IOException("网络断了");
            }
            return new ChatMessage("assistant", "步骤 " + index + " 完成");
        }));
        WorkflowRunner.Summary summary = runner.run(created.id());
        assertEquals(WorkflowStatus.FAILED, summary.status());
        WorkflowRecord failed = store.load(created.id());
        assertEquals(StepStatus.COMPLETED, failed.steps().get(0).status());
        assertEquals(StepStatus.FAILED, failed.steps().get(1).status());
        assertEquals(StepStatus.PENDING, failed.steps().get(2).status());
        assertNotNull(failed.steps().get(1).finishedAt());
        assertTrue(failed.lastError().contains("步骤 2 失败"), failed.lastError());
        assertTrue(summary.render().contains("最近错误：步骤 2 失败"), summary.render());
        assertTrue(summary.render().contains("可修复后再次调用 workflow_run"), summary.render());
        assertEquals(2, prompts.size(), "失败后不再执行第三步");
        assertTrue(buffer.toString().contains("修复后再次运行可从第 2 步继续"), buffer.toString());
    }

    @Test
    void subagentLimitCountsAsFailure() throws Exception {
        WorkflowStore store = store();
        WorkflowRecord created = store.create("到上限", List.of("一直调用工具"), T0);
        buffer.getBuffer().setLength(0);
        SubagentRunner sub = new SubagentRunner(new ModelClient() {
            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                return ChatMessage.assistant("还要继续", List.of(new dev.huagent.tool.ToolCall(
                        "c1", "missing", "{}")));
            }
        }, new ToolRegistry(List.of()), 2, "子代理", new PrintWriter(buffer));
        WorkflowRunner runner = new WorkflowRunner(store, sub, java.time.Clock.systemDefaultZone(),
                new PrintWriter(buffer));
        WorkflowRunner.Summary summary = runner.run(created.id());
        assertEquals(WorkflowStatus.FAILED, summary.status());
        WorkflowRecord failed = store.load(created.id());
        assertEquals(StepStatus.FAILED, failed.steps().get(0).status());
        assertTrue(failed.steps().get(0).result().startsWith("子代理未完成"), failed.steps().get(0).result());
        assertTrue(failed.lastError().contains("子代理未完成"), failed.lastError());
    }

    @Test
    void resumeFromFailedStartsAtFailedStep() throws Exception {
        WorkflowStore store = store();
        WorkflowRecord created = store.create("修复后继续", List.of("一步", "两步"), T0);
        List<String> prompts = new ArrayList<>();
        WorkflowRunner first = runner(store, model(store, prompts, (index, prompt, current) -> {
            if (index == 2) {
                throw new IOException("第一次失败");
            }
            return new ChatMessage("assistant", "第一步完成");
        }));
        assertEquals(WorkflowStatus.FAILED, first.run(created.id()).status());
        prompts.clear();

        WorkflowRunner second = runner(store, model(store, prompts, (index, prompt, current) -> {
            assertTrue(prompt.startsWith("两步"), "从失败步骤重跑：" + prompt);
            return new ChatMessage("assistant", "这次成功");
        }));
        WorkflowRunner.Summary summary = second.run(created.id());
        assertEquals(WorkflowStatus.COMPLETED, summary.status());
        assertEquals(1, prompts.size(), "已完成的步骤不重跑");
        WorkflowRecord done = store.load(created.id());
        assertEquals("第一步完成", done.steps().get(0).result());
        assertEquals("这次成功", done.steps().get(1).result());
        assertNull(done.lastError());
    }

    @Test
    void interruptMarksPausedAndResetsStep() throws Exception {
        WorkflowStore store = store();
        WorkflowRecord created = store.create("中断", List.of("一步", "两步"), T0);
        List<String> prompts = new ArrayList<>();
        WorkflowRunner runner = runner(store, model(store, prompts, (index, prompt, current) -> {
            if (index == 2) {
                throw new InterruptedException();
            }
            return new ChatMessage("assistant", "步骤 " + index + " 完成");
        }));
        assertThrows(InterruptedException.class, () -> runner.run(created.id()));
        WorkflowRecord paused = store.load(created.id());
        assertEquals(WorkflowStatus.PAUSED, paused.status());
        assertEquals(StepStatus.COMPLETED, paused.steps().get(0).status());
        assertEquals(StepStatus.PENDING, paused.steps().get(1).status());
        assertNull(paused.steps().get(1).startedAt());
        assertTrue(buffer.toString().contains("再次运行可从第 2 步继续"), buffer.toString());

        // paused 可以继续：第二步重跑。
        prompts.clear();
        WorkflowRunner resumed = runner(store, model(store, prompts, (index, prompt, current) ->
                new ChatMessage("assistant", "续上")));
        assertEquals(WorkflowStatus.COMPLETED, resumed.run(created.id()).status());
        assertTrue(prompts.get(0).startsWith("两步"), prompts.get(0));
    }

    @Test
    void refusesRunningCompletedAndCancelled() throws Exception {
        WorkflowStore store = store();
        WorkflowRecord created = store.create("终态", List.of("一步"), T0);
        List<String> prompts = new ArrayList<>();
        WorkflowRunner runner = runner(store, model(store, prompts, (index, prompt, current) ->
                new ChatMessage("assistant", "好")));

        WorkflowRecord running = new WorkflowRecord(created.id(), created.name(), WorkflowStatus.RUNNING,
                List.of(WorkflowStep.pending("一步").markRunning(T0)), T0, T0, null);
        store.save(running);
        IllegalArgumentException runningError = assertThrows(IllegalArgumentException.class,
                () -> runner.run(created.id()));
        assertTrue(runningError.getMessage().contains("已在执行中"));

        store.save(new WorkflowRecord(created.id(), created.name(), WorkflowStatus.COMPLETED,
                List.of(WorkflowStep.pending("一步").markRunning(T0).finish(StepStatus.COMPLETED, "好", T0)),
                T0, T0, null));
        IllegalArgumentException completedError = assertThrows(IllegalArgumentException.class,
                () -> runner.run(created.id()));
        assertTrue(completedError.getMessage().contains("已全部完成"));

        store.save(created.withStatus(WorkflowStatus.CANCELLED, T0));
        IllegalArgumentException cancelledError = assertThrows(IllegalArgumentException.class,
                () -> runner.run(created.id()));
        assertTrue(cancelledError.getMessage().contains("已取消"));
        assertEquals(0, prompts.size(), "三种状态都不发起模型请求");
    }

    private static String firstLine(String text) {
        return text.split("\n", 2)[0];
    }
}
