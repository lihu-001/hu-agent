package dev.huagent.schedule;

import dev.huagent.background.BackgroundManager;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleToolsTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:30Z");
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final Pattern ID = Pattern.compile("schedule_[0-9a-f]{8}");

    @TempDir
    Path root;
    private ScheduleManager manager;
    private BackgroundManager background;
    private Map<String, AgentTool> tools;

    @BeforeEach
    void setUp() throws Exception {
        Path directory = Files.createDirectories(root.resolve(ScheduleStore.DIRECTORY_NAME));
        ScheduleStore store = new ScheduleStore(directory);
        background = new BackgroundManager(new NoopTool(), line -> {
        });
        manager = new ScheduleManager(store, background, Duration.ofSeconds(15), UTC,
                Clock.fixed(NOW, UTC), line -> {
                });
        tools = new HashMap<>();
        for (AgentTool tool : ScheduleTools.create(manager, "测试工作目录")) {
            tools.put(tool.definition().name(), tool);
        }
    }

    @AfterEach
    void tearDown() {
        manager.close();
        background.shutdown();
    }

    @Test
    void registersSixToolsWithSchemas() {
        assertEquals(java.util.Set.copyOf(ScheduleTools.NAMES), tools.keySet());
        assertEquals(6, tools.size());
        assertEquals("object", tools.get(ScheduleTools.CREATE).definition().parameters().getString("type"));
    }

    @Test
    void createValidatesAndReturnsNextRun() throws Exception {
        String output = execute(ScheduleTools.CREATE, Map.of("name", "早间构建",
                "cron", "0 9 * * 1-5", "command", "mvn -q test"));
        Matcher matcher = ID.matcher(output);
        assertTrue(matcher.find(), output);
        String id = matcher.group();
        assertTrue(output.contains("下一次 2026-01-01 09:00"), output);
        assertTrue(Files.exists(root.resolve(ScheduleStore.DIRECTORY_NAME).resolve(id + ".json")));

        String json = execute(ScheduleTools.GET, Map.of("id", id));
        assertTrue(json.contains("\"name\":\"早间构建\""), json);
        assertTrue(json.contains("\"command\":\"mvn -q test\""), json);
        assertTrue(json.contains("\"enabled\":true"), json);
    }

    @Test
    void invalidArgumentsAreRejectedBeforeTouchingFiles() {
        assertThrows(IllegalArgumentException.class, () -> execute(ScheduleTools.CREATE,
                Map.of("name", "x", "cron", "bad", "command", "echo x")));
        assertThrows(IllegalArgumentException.class, () -> execute(ScheduleTools.CREATE,
                Map.of("name", "x", "cron", "* * * * *")));
        assertThrows(IllegalArgumentException.class, () -> execute(ScheduleTools.CREATE,
                Map.of("name", "", "cron", "* * * * *", "command", "echo x")));
        assertThrows(IllegalArgumentException.class, () -> tools.get(ScheduleTools.CREATE)
                .execute("{\"name\":\"x\",\"cron\":\"* * * * *\",\"command\":\"x\",\"extra\":1}"));
        assertThrows(IllegalArgumentException.class, () -> tools.get(ScheduleTools.LIST).execute("{\"x\":1}"));
        assertThrows(IllegalArgumentException.class, () -> execute(ScheduleTools.GET, Map.of("id", "schedule_XYZ")));
        assertThrows(IllegalArgumentException.class, () -> execute(ScheduleTools.GET,
                Map.of("id", "schedule_ffffffff")));
    }

    @Test
    void listPauseResumeAndDeleteRoundTrip() throws Exception {
        assertTrue(execute(ScheduleTools.LIST, Map.of()).contains("没有定时计划"));
        String created = execute(ScheduleTools.CREATE, Map.of("name", "整点检查",
                "cron", "0 * * * *", "command", "echo check"));
        String id = id(created);
        String list = execute(ScheduleTools.LIST, Map.of());
        assertTrue(list.contains("[on] " + id + "：整点检查"), list);
        assertTrue(list.contains("下次=2026-01-01 01:00"), list);

        assertTrue(execute(ScheduleTools.PAUSE, Map.of("id", id)).contains("已暂停"));
        assertTrue(execute(ScheduleTools.LIST, Map.of()).contains("[off] " + id), list);
        assertTrue(execute(ScheduleTools.GET, Map.of("id", id)).contains("\"nextRunAt\":null"));

        String resumed = execute(ScheduleTools.RESUME, Map.of("id", id));
        assertTrue(resumed.contains("下一次 2026-01-01 01:00"), resumed);

        assertTrue(execute(ScheduleTools.DELETE, Map.of("id", id)).contains("已删除"));
        assertTrue(execute(ScheduleTools.LIST, Map.of()).contains("没有定时计划"));
    }

    @Test
    void renderedScheduleIncludesLastRun() throws Exception {
        String created = execute(ScheduleTools.CREATE, Map.of("name", "报表",
                "cron", "30 8 * * *", "command", "echo report"));
        ScheduleRecord record = manager.get(id(created));
        ScheduleStore store = new ScheduleStore(root.resolve(ScheduleStore.DIRECTORY_NAME));
        store.save(record.withRunStarted(Instant.parse("2026-01-01T08:30:00Z"),
                Instant.parse("2026-01-02T08:30:00Z")).withRunFinished(ScheduleRunStatus.COMPLETED,
                Instant.parse("2026-01-01T08:30:01Z"), "ok"));
        String line = execute(ScheduleTools.LIST, Map.of());
        assertTrue(line.contains("[x]completed@2026-01-01 08:30"), line);
        assertTrue(line.contains("触发=1 次"), line);
    }

    private String execute(String tool, Map<String, Object> arguments) throws Exception {
        return tools.get(tool).execute(JSON.toJSONString(arguments));
    }

    private static String id(String created) {
        Matcher matcher = ID.matcher(created);
        assertTrue(matcher.find(), created);
        return matcher.group();
    }

    /** schedule_create 的权限与测试都不启动命令，这里只需要一个不会运行的工具。 */
    private static final class NoopTool implements AgentTool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("noop", "不执行", JSON.parseObject("{\"type\":\"object\"}"));
        }

        @Override
        public String execute(String arguments) {
            return "退出码：0";
        }
    }
}
