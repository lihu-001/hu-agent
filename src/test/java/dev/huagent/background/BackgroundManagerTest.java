package dev.huagent.background;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ShellTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundManagerTest {
    @TempDir
    Path root;
    private final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    private final List<String> terminal = new CopyOnWriteArrayList<>();
    private final GatedShell shell = new GatedShell();
    private final BackgroundManager manager = new BackgroundManager(shell, terminal::add);

    @AfterEach
    void tearDown() {
        shell.releaseAll();
        manager.shutdown();
    }

    @Test
    void startReturnsImmediatelyAndNotifiesOnceAfterCompletion() throws Exception {
        BackgroundTask task = manager.start("build");
        assertEquals("bg_1", task.id());
        assertEquals(BackgroundStatus.RUNNING, task.status());
        assertNull(task.result());
        assertTrue(manager.takeNotifications().isEmpty(), "运行中没有通知");

        shell.release("build", "退出码：0\n构建成功");
        List<String> notices = awaitNotifications(1);
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).startsWith("[bg_1] completed，耗时 "), notices.get(0));
        assertTrue(notices.get(0).contains("命令：build\n退出码：0\n构建成功"), notices.get(0));
        assertTrue(manager.takeNotifications().isEmpty(), "每条结果只通知一次");
        assertEquals(BackgroundStatus.COMPLETED, manager.get("bg_1").status());
        assertEquals("退出码：0\n构建成功", manager.get("bg_1").result());
        assertTrue(terminal.stream().anyMatch(line -> line.startsWith("后台> bg_1 completed")), terminal.toString());
    }

    @Test
    void statusFollowsShellResult() {
        assertEquals(BackgroundStatus.COMPLETED, BackgroundManager.statusOf("退出码：0\nok"));
        assertEquals(BackgroundStatus.COMPLETED, BackgroundManager.statusOf("退出码：0"));
        assertEquals(BackgroundStatus.FAILED, BackgroundManager.statusOf("退出码：1\nerror"));
        assertEquals(BackgroundStatus.FAILED, BackgroundManager.statusOf("退出码：01"));
        assertEquals(BackgroundStatus.TIMED_OUT, BackgroundManager.statusOf("工具错误：命令超时（100 ms），已请求终止进程树。\n"));
    }

    @Test
    void tasksFinishOutOfOrderAndNotificationsKeepCompletionOrder() throws Exception {
        manager.start("slow");
        manager.start("fast");
        shell.release("fast", "退出码：0\nfast");
        awaitTerminal("bg_2");
        shell.release("slow", "退出码：3\nslow");
        awaitTerminal("bg_1");
        List<String> notices = manager.takeNotifications();
        assertEquals(2, notices.size());
        assertTrue(notices.get(0).startsWith("[bg_2] completed"), notices.get(0));
        assertTrue(notices.get(1).startsWith("[bg_1] failed"), notices.get(1));

        manager.requeue(notices);
        assertEquals(notices, manager.takeNotifications(), "放回后顺序不变");
    }

    @Test
    void limitsRunningAndTotalTasks() throws Exception {
        for (int i = 0; i < BackgroundManager.MAX_RUNNING; i++) {
            manager.start("c" + i);
        }
        IllegalArgumentException running = assertThrows(IllegalArgumentException.class, () -> manager.start("more"));
        assertTrue(running.getMessage().contains("最多 " + BackgroundManager.MAX_RUNNING), running.getMessage());
        shell.releaseAll();
        awaitAllFinished();
        for (int i = BackgroundManager.MAX_RUNNING; i < BackgroundManager.MAX_TASKS; i++) {
            manager.start("c" + i);
            shell.releaseAll();
            awaitAllFinished();
        }
        assertEquals(BackgroundManager.MAX_TASKS, manager.list().size());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> manager.start("x"))
                .getMessage().contains("最多启动 " + BackgroundManager.MAX_TASKS));
    }

    @Test
    void shellExceptionBecomesFailedNotification() throws Exception {
        BackgroundManager broken = new BackgroundManager(new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("shell", "", JSON.parseObject("{}"));
            }

            @Override
            public String execute(String arguments) {
                throw new IllegalStateException("boom");
            }
        }, terminal::add);
        broken.start("x");
        List<String> notices = List.of();
        for (int i = 0; i < 200 && notices.isEmpty(); i++) {
            Thread.sleep(10);
            notices = broken.takeNotifications();
        }
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("failed") && notices.get(0).contains("IllegalStateException"), notices.get(0));
        broken.shutdown();
    }

    @Test
    void unknownIdIsError() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> manager.get("bg_9"))
                .getMessage().contains("bg_9"));
    }

    @Test
    void shutdownCancelsRunningWithoutNotificationAndRejectsNewTasks() throws Exception {
        manager.start("forever");
        assertEquals(1, manager.shutdown());
        assertEquals(BackgroundStatus.CANCELLED, manager.get("bg_1").status());
        assertTrue(manager.takeNotifications().isEmpty(), "会话结束后的取消不再通知");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> manager.start("x"))
                .getMessage().contains("会话正在结束"));
        assertEquals(0, manager.shutdown());
    }

    @Test
    void longOutputIsTruncatedInNotificationButCheckKeepsFullResult() throws Exception {
        manager.start("big");
        String result = "退出码：0\n" + "x".repeat(BackgroundManager.MAX_PREVIEW_CHARS * 2);
        shell.release("big", result);
        String notice = awaitNotifications(1).get(0);
        assertTrue(notice.contains("用 background_check 查看完整输出"), notice);
        assertTrue(notice.length() < result.length());
        assertEquals(result, manager.get("bg_1").result());
    }

    @Test
    void realShellRunsInBackgroundAndShutdownStopsLongCommand() throws Exception {
        BackgroundManager real = new BackgroundManager(new ShellTool(root, Duration.ofSeconds(30), 1000), terminal::add);
        real.start(windows ? "Write-Output 后台完成" : "echo 后台完成");
        real.start(windows ? "Start-Sleep -Seconds 20; Set-Content late.txt done" : "sleep 20; echo done > late.txt");
        List<String> notices = List.of();
        for (int i = 0; i < 300 && notices.isEmpty(); i++) {
            Thread.sleep(50);
            notices = real.takeNotifications();
        }
        assertEquals(1, notices.size(), "只有短命令完成");
        assertTrue(notices.get(0).contains("[bg_1] completed") && notices.get(0).contains("后台完成"), notices.get(0));
        assertEquals(BackgroundStatus.RUNNING, real.get("bg_2").status());
        long started = System.nanoTime();
        assertEquals(1, real.shutdown());
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10), "取消不等待命令自然结束");
        assertEquals(BackgroundStatus.CANCELLED, real.get("bg_2").status());
        Thread.sleep(500);
        assertFalse(Files.exists(root.resolve("late.txt")), "被取消的命令没有继续执行");
    }

    @Test
    void realShellTimeoutIsReported() throws Exception {
        BackgroundManager real = new BackgroundManager(new ShellTool(root, Duration.ofMillis(300), 1000), terminal::add);
        real.start(windows ? "Start-Sleep -Seconds 20" : "sleep 20");
        List<String> notices = List.of();
        for (int i = 0; i < 300 && notices.isEmpty(); i++) {
            Thread.sleep(50);
            notices = real.takeNotifications();
        }
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).startsWith("[bg_1] timed_out"), notices.get(0));
        real.shutdown();
    }

    private List<String> awaitNotifications(int count) throws InterruptedException {
        List<String> notices = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 500 && notices.size() < count; i++) {
            notices.addAll(manager.takeNotifications());
            if (notices.size() < count) {
                Thread.sleep(10);
            }
        }
        return notices;
    }

    private void awaitTerminal(String id) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (terminal.stream().anyMatch(line -> line.startsWith("后台> " + id + " "))) {
                return;
            }
            Thread.sleep(10);
        }
        fail("没有等到 " + id + " 完成：" + terminal);
    }

    private void awaitAllFinished() throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (manager.list().stream().noneMatch(task -> task.status() == BackgroundStatus.RUNNING)) {
                return;
            }
            Thread.sleep(10);
        }
        fail("后台任务没有结束");
    }

    /** 按命令阻塞的假 Shell：测试决定每条命令何时结束、返回什么。 */
    static final class GatedShell implements AgentTool {
        private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        private final Map<String, String> results = new ConcurrentHashMap<>();
        private volatile boolean releaseAll;

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("shell", "fake", JSON.parseObject("{}"));
        }

        @Override
        public String execute(String arguments) throws InterruptedException {
            String command = JSON.parseObject(arguments).getString("command");
            CountDownLatch gate = gates.computeIfAbsent(command, key -> new CountDownLatch(1));
            if (!releaseAll) {
                gate.await();
            }
            gates.remove(command);
            return results.getOrDefault(command, "退出码：0\n" + command + " done");
        }

        void release(String command, String result) {
            results.put(command, result);
            gates.computeIfAbsent(command, key -> new CountDownLatch(1)).countDown();
        }

        void releaseAll() {
            releaseAll = true;
            gates.values().forEach(CountDownLatch::countDown);
        }
    }
}
