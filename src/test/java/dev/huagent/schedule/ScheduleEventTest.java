package dev.huagent.schedule;

import dev.huagent.background.BackgroundManager;
import dev.huagent.schedule.ScheduleManager;
import dev.huagent.schedule.ScheduleRecord;
import dev.huagent.schedule.ScheduleStore;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.alibaba.fastjson2.JSON;

import static org.junit.jupiter.api.Assertions.*;

/** S15：定时计划的非取消终态送达事件中心；取消（会话结束）不产生对话事件。 */
class ScheduleEventTest {
    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("等待超时");
            }
            Thread.sleep(20);
        }
    }

    private static AgentTool instantShell() {
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("shell", "测试用", JSON.parseObject("{\"type\":\"object\"}"));
            }

            @Override
            public String execute(String arguments) {
                return "退出码：0\nok";
            }
        };
    }

    private static AgentTool slowShell(AtomicInteger started) {
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("shell", "测试用", JSON.parseObject("{\"type\":\"object\"}"));
            }

            @Override
            public String execute(String arguments) throws InterruptedException {
                started.incrementAndGet();
                Thread.sleep(30_000);
                return "退出码：0\n不该到这里";
            }
        };
    }

    @Test
    void completedSchedulePublishesEventToSink(@TempDir Path root) throws Exception {
        List<String> events = new ArrayList<>();
        BackgroundManager background = new BackgroundManager(instantShell(), line -> { });
        ScheduleManager manager = new ScheduleManager(new ScheduleStore(root.resolve(".schedules")),
                background, Duration.ofSeconds(15), ZoneId.systemDefault(), Clock.systemDefaultZone(),
                line -> { }, events::add);
        ScheduleRecord created = manager.create("备份", "* * * * *", "echo hi");

        manager.tick(Instant.now().plus(Duration.ofHours(1)));

        await(() -> !events.isEmpty());
        assertEquals(1, events.size());
        assertTrue(events.get(0).contains(created.id()));
        assertTrue(events.get(0).contains("备份"));
        assertTrue(events.get(0).contains("completed"));
        assertTrue(events.get(0).contains("ok"));
        background.shutdown();
    }

    @Test
    void cancelledScheduleDoesNotPublish(@TempDir Path root) throws Exception {
        List<String> events = new ArrayList<>();
        AtomicInteger started = new AtomicInteger();
        BackgroundManager background = new BackgroundManager(slowShell(started), line -> { });
        ScheduleManager manager = new ScheduleManager(new ScheduleStore(root.resolve(".schedules")),
                background, Duration.ofSeconds(15), ZoneId.systemDefault(), Clock.systemDefaultZone(),
                line -> { }, events::add);
        manager.create("长任务", "* * * * *", "sleep forever");

        manager.tick(Instant.now().plus(Duration.ofHours(1)));
        await(() -> started.get() > 0);
        // 会话结束时取消运行中的计划命令：结果写回计划文件，但不产生对话事件。
        background.shutdown();
        Thread.sleep(200);
        assertTrue(events.isEmpty());
    }
}
