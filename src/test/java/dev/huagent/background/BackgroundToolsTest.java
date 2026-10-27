package dev.huagent.background;

import dev.huagent.tool.AgentTool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundToolsTest {
    private final BackgroundManagerTest.GatedShell shell = new BackgroundManagerTest.GatedShell();
    private final BackgroundManager manager = new BackgroundManager(shell, line -> { });
    private final List<AgentTool> tools = BackgroundTools.create(manager, "测试环境。");

    @AfterEach
    void tearDown() {
        shell.releaseAll();
        manager.shutdown();
    }

    @Test
    void definitionsDescribeAutomaticNotification() {
        assertEquals(BackgroundTools.NAMES, tools.stream().map(tool -> tool.definition().name()).toList());
        String run = tools.get(0).definition().description();
        assertTrue(run.contains("测试环境。") && run.contains("<background-results>") && run.contains("不需要轮询"), run);
    }

    @Test
    void runReturnsIdAndCheckShowsStatusThenFullOutput() throws Exception {
        assertEquals("本次会话没有后台任务。", tools.get(1).execute("{}"));
        String started = tools.get(0).execute("{\"command\":\"mvn test\"}");
        assertTrue(started.startsWith("已在后台启动 bg_1：mvn test"), started);
        assertTrue(tools.get(1).execute("{}").startsWith("bg_1 [running] "), tools.get(1).execute("{}"));
        assertTrue(tools.get(1).execute("{\"id\":\"bg_1\"}").endsWith("（仍在运行，暂无输出）"));

        shell.release("mvn test", "退出码：0\nBUILD SUCCESS");
        for (int i = 0; i < 500 && manager.get("bg_1").status() == BackgroundStatus.RUNNING; i++) {
            Thread.sleep(10);
        }
        String checked = tools.get(1).execute("{\"id\":\" bg_1 \"}");
        assertTrue(checked.startsWith("bg_1 [completed] ") && checked.endsWith("退出码：0\nBUILD SUCCESS"), checked);
    }

    @Test
    void rejectsInvalidArguments() {
        for (String arguments : List.of("{}", "{\"command\":\" \"}", "{\"command\":1}", "{\"command\":\"a\",\"x\":1}", "[")) {
            assertThrows(IllegalArgumentException.class, () -> tools.get(0).execute(arguments), arguments);
        }
        for (String arguments : List.of("{\"id\":\"\"}", "{\"id\":1}", "{\"name\":\"bg_1\"}")) {
            assertThrows(IllegalArgumentException.class, () -> tools.get(1).execute(arguments), arguments);
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> tools.get(1).execute("{\"id\":\"bg_7\"}"))
                .getMessage().contains("没有后台任务 bg_7"));
        assertTrue(manager.list().isEmpty(), "参数错误不启动任务");
    }
}
