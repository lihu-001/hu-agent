package dev.huagent.workflow;

import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.subagent.SubagentRunner;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.tool.ToolRegistry;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowToolsTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path root;

    private WorkflowStore store() {
        return new WorkflowStore(root.resolve(WorkflowStore.DIRECTORY_NAME));
    }

    private WorkflowRunner runner(WorkflowStore store) {
        StringWriter buffer = new StringWriter();
        SubagentRunner sub = new SubagentRunner(new ModelClient() {
            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                return new ChatMessage("assistant", "子代理回复");
            }
        }, new ToolRegistry(List.of()), 3, "子代理", new PrintWriter(buffer));
        return new WorkflowRunner(store, sub, java.time.Clock.systemDefaultZone(), new PrintWriter(buffer));
    }

    private AgentTool tool(List<AgentTool> tools, String name) {
        return tools.stream().filter(t -> name.equals(t.definition().name())).findFirst().orElseThrow();
    }

    @Test
    void exposesExactlyFiveTools() {
        List<AgentTool> tools = WorkflowTools.create(store(), runner(store()));
        assertEquals(List.of("workflow_create", "workflow_run", "workflow_list", "workflow_get", "workflow_cancel"),
                tools.stream().map(t -> t.definition().name()).toList());
        assertTrue(WorkflowTools.NAMES.size() == tools.size());
        assertFalse(WorkflowTools.RULE.isEmpty());
    }

    @Test
    void createValidatesArgumentsAndReturnsStepList() throws Exception {
        WorkflowStore store = store();
        AgentTool create = tool(WorkflowTools.create(store, runner(store)), WorkflowTools.CREATE);
        assertThrows(IllegalArgumentException.class, () -> create.execute("不是JSON"));
        assertThrows(IllegalArgumentException.class, () -> create.execute(
                JSON.toJSONString(Map.of("name", "工作流"))));
        assertThrows(IllegalArgumentException.class, () -> create.execute(
                JSON.toJSONString(Map.of("name", "工作流", "steps", List.of(), "extra", 1))));
        assertThrows(IllegalArgumentException.class, () -> create.execute(
                JSON.toJSONString(Map.of("name", "工作流", "steps", List.of("   ")))));
        assertThrows(IllegalArgumentException.class, () -> create.execute(
                JSON.toJSONString(Map.of("name", "工作流", "steps", List.of(1, 2)))));

        String text = create.execute(JSON.toJSONString(Map.of("name", "发布流程", "steps",
                List.of("跑测试", "写发布说明"))));
        assertTrue(text.contains("状态 pending"), text);
        assertTrue(text.contains("[ ] 步骤 1：跑测试"), text);
        assertTrue(text.contains("[ ] 步骤 2：写发布说明"), text);
        assertEquals(1, store.list(w -> fail(w)).size());
    }

    @Test
    void runReturnsSummaryAndPersistsCompletion() throws Exception {
        WorkflowStore store = store();
        List<AgentTool> tools = WorkflowTools.create(store, runner(store));
        String id = idOf(tool(tools, WorkflowTools.CREATE).execute(
                JSON.toJSONString(Map.of("name", "两步", "steps", List.of("一步", "两步")))));
        String text = tool(tools, WorkflowTools.RUN).execute(JSON.toJSONString(Map.of("id", id)));
        assertTrue(text.contains("状态：completed"), text);
        assertTrue(text.contains("子代理回复"), text);
        assertEquals(WorkflowStatus.COMPLETED, store.load(id).status());
    }

    @Test
    void runRejectsUnknownOrBadId() throws Exception {
        WorkflowStore store = store();
        AgentTool run = tool(WorkflowTools.create(store, runner(store)), WorkflowTools.RUN);
        assertThrows(IllegalArgumentException.class, () -> run.execute("{}"));
        assertThrows(IllegalArgumentException.class,
                () -> run.execute(JSON.toJSONString(Map.of("id", "task_0a1b2c3d"))));
        assertThrows(IllegalArgumentException.class,
                () -> run.execute(JSON.toJSONString(Map.of("id", "workflow_00000000"))));
    }

    @Test
    void listAndGetExposeState() throws Exception {
        WorkflowStore store = store();
        List<AgentTool> tools = WorkflowTools.create(store, runner(store));
        AgentTool create = tool(tools, WorkflowTools.CREATE);
        String id = idOf(create.execute(JSON.toJSONString(Map.of("name", "查看", "steps", List.of("一步")))));

        String listed = tool(tools, WorkflowTools.LIST).execute("{}");
        assertTrue(listed.contains("状态=pending"), listed);
        assertTrue(listed.contains("进度=0/1"), listed);

        String json = tool(tools, WorkflowTools.GET).execute(JSON.toJSONString(Map.of("id", id)));
        assertEquals(id, JSON.parseObject(json).getString("id"));
        assertTrue(json.contains("\"prompt\""), json);
        assertTrue(json.contains("\"result\""), json);
        assertThrows(IllegalArgumentException.class, () -> tool(tools, WorkflowTools.GET).execute("{}"));
    }

    @Test
    void cancelLifecycleRules() throws Exception {
        WorkflowStore store = store();
        List<AgentTool> tools = WorkflowTools.create(store, runner(store));
        AgentTool create = tool(tools, WorkflowTools.CREATE);
        AgentTool cancel = tool(tools, WorkflowTools.CANCEL);
        AgentTool run = tool(tools, WorkflowTools.RUN);

        // completed 不能取消
        String doneId = idOf(create.execute(JSON.toJSONString(Map.of("name", "已完成", "steps", List.of("一步")))));
        run.execute(JSON.toJSONString(Map.of("id", doneId)));
        IllegalArgumentException completedError = assertThrows(IllegalArgumentException.class,
                () -> cancel.execute(JSON.toJSONString(Map.of("id", doneId))));
        assertTrue(completedError.getMessage().contains("已全部完成"));

        // running 不能取消
        String runningId = idOf(create.execute(JSON.toJSONString(Map.of("name", "执行中", "steps", List.of("一步")))));
        store.save(new WorkflowRecord(runningId, "执行中", WorkflowStatus.RUNNING,
                List.of(WorkflowStep.pending("一步").markRunning(T0)), T0, T0, null));
        IllegalArgumentException runningError = assertThrows(IllegalArgumentException.class,
                () -> cancel.execute(JSON.toJSONString(Map.of("id", runningId))));
        assertTrue(runningError.getMessage().contains("正在执行"));

        // pending → cancelled，幂等
        String pendingId = idOf(create.execute(JSON.toJSONString(Map.of("name", "待取消", "steps", List.of("一步")))));
        String text = cancel.execute(JSON.toJSONString(Map.of("id", pendingId)));
        assertTrue(text.contains("已取消"), text);
        assertEquals(WorkflowStatus.CANCELLED, store.load(pendingId).status());
        String again = cancel.execute(JSON.toJSONString(Map.of("id", pendingId)));
        assertTrue(again.contains("已是取消状态"), again);
        // cancelled 不能再运行
        IllegalArgumentException runError = assertThrows(IllegalArgumentException.class,
                () -> run.execute(JSON.toJSONString(Map.of("id", pendingId))));
        assertTrue(runError.getMessage().contains("已取消"));
    }

    private static String idOf(String createdText) {
        int start = createdText.indexOf("workflow_");
        assertTrue(start >= 0, createdText);
        int end = createdText.indexOf('：', start);
        return createdText.substring(start, end > start ? end : start + "workflow_".length() + 8);
    }
}
