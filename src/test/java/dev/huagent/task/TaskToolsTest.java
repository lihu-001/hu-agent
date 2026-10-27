package dev.huagent.task;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TaskToolsTest {
    @TempDir
    Path root;
    private TaskStore store;
    private Map<String, AgentTool> tools;

    @BeforeEach
    void setUp() {
        store = new TaskStore(root.resolve(TaskStore.DIRECTORY_NAME));
        tools = TaskTools.create(store, TaskTools.DEFAULT_OWNER).stream()
                .collect(Collectors.toMap(tool -> tool.definition().name(), tool -> tool));
    }

    @Test
    void definesSixToolsWithoutOwnerParameter() {
        assertEquals(TaskTools.NAMES, TaskTools.create(store, "agent").stream()
                .map(AgentTool::definition).map(ToolDefinition::name).toList());
        for (AgentTool tool : tools.values()) {
            assertFalse(tool.definition().parameters().getJSONObject("properties").containsKey("owner"),
                    "owner 由程序固定，模型不能指定");
        }
    }

    @Test
    void fullLifecycleThroughTools() throws Exception {
        String schema = idOf(run("task_create", Map.of("subject", "建表", "description", "细节")));
        String api = idOf(run("task_create", Map.of("subject", "接口")));
        assertTrue(run("task_update", Map.of("id", api, "addBlockedBy", List.of(schema)))
                .startsWith("已更新 " + api + " 依赖：" + schema));
        assertTrue(run("task_list", Map.of()).contains("阻塞于=" + schema));
        assertEquals("细节", JSON.parseObject(run("task_get", Map.of("id", schema))).getString("description"));
        assertTrue(run("task_claim", Map.of("id", schema)).contains("认领者 agent"));
        String done = run("task_complete", Map.of("id", schema));
        assertTrue(done.startsWith("已完成 " + schema), done);
        assertTrue(done.contains("已解锁：" + api + "（接口）"), done);
    }

    @Test
    void argumentsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> run("task_create", Map.of("subject", "a", "owner", "x")));
        assertThrows(IllegalArgumentException.class, () -> run("task_create", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> run("task_create", Map.of("subject", "a", "description", 1)));
        assertThrows(IllegalArgumentException.class, () -> run("task_list", Map.of("x", 1)));
        assertThrows(IllegalArgumentException.class, () -> tools.get("task_get").execute("[1]"));
        assertThrows(IllegalArgumentException.class, () -> run("task_update", Map.of("id", "task_12345678",
                "addBlockedBy", List.of())));
        assertThrows(IllegalArgumentException.class, () -> run("task_update", Map.of("id", "task_12345678",
                "addBlockedBy", List.of(1))));
        assertThrows(IllegalArgumentException.class, () -> run("task_claim", Map.of("id", "task_12345678")));
    }

    @Test
    void listWithNoTasksAndEmptyArguments() throws Exception {
        assertEquals("没有任务。用 task_create 创建。", tools.get("task_list").execute(""));
        assertEquals("没有任务。用 task_create 创建。", tools.get("task_list").execute("{}"));
    }

    private String run(String name, Map<String, ?> args) throws Exception {
        return tools.get(name).execute(JSON.toJSONString(args));
    }

    static String idOf(String created) {
        Matcher matcher = Pattern.compile("task_[0-9a-f]{8}").matcher(created);
        assertTrue(matcher.find(), created);
        return matcher.group();
    }
}
