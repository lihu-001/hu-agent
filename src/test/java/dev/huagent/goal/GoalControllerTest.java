package dev.huagent.goal;

import dev.huagent.background.BackgroundManager;
import dev.huagent.model.ChatMessage;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class GoalControllerTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<ChatMessage> HISTORY = List.of(new ChatMessage("user", "任务"),
            new ChatMessage("assistant", "做完了"));

    private final Deque<String> replies = new ArrayDeque<>();
    private int evaluatorCalls;
    private final MutableClock clock = new MutableClock(START);
    private final AtomicReference<String> pending = new AtomicReference<>();
    private final StringWriter buffer = new StringWriter();
    private final PrintWriter output = new PrintWriter(buffer, true);

    @Test
    void withoutGoalAllowsWithoutCallingEvaluator() throws Exception {
        GoalController goal = controller(3);
        assertFalse(goal.active());
        assertEquals(GoalDecision.ALLOW, goal.afterTurn(HISTORY, output));
        assertEquals(0, evaluatorCalls);
        assertEquals("", buffer.toString(), "没有目标时不打印任何内容");
    }

    @Test
    void achievedClearsGoal() throws Exception {
        GoalController goal = controller(3);
        assertEquals("目标> 已设置，开始工作：测试通过", goal.set("测试通过"));
        clock.advance(Duration.ofSeconds(42));
        replies.add(ok("退出码 0"));
        GoalDecision decision = goal.afterTurn(HISTORY, output);
        assertEquals(GoalDecision.Action.ACHIEVED, decision.action());
        assertEquals("退出码 0", decision.reason());
        assertFalse(goal.active());
        assertTrue(buffer.toString().contains("目标> 已达成（判断 1 次，用时 42 秒）：退出码 0"), buffer.toString());
    }

    @Test
    void impossibleClearsGoalAsFailed() throws Exception {
        GoalController goal = controller(3);
        goal.set("删除生产库");
        replies.add("{\"ok\":false,\"reason\":\"用户拒绝了删除\",\"impossible\":true}");
        assertEquals(GoalDecision.Action.FAILED, goal.afterTurn(HISTORY, output).action());
        assertFalse(goal.active());
        assertTrue(buffer.toString().contains("判断为无法完成，已清除：用户拒绝了删除"), buffer.toString());
    }

    @Test
    void notOkBlocksUntilCapThenLimitKeepsGoal() throws Exception {
        GoalController goal = controller(2);
        goal.set("测试通过");
        for (int i = 0; i < 3; i++) {
            replies.add(notOk("还没运行测试 " + i));
        }
        GoalDecision first = goal.afterTurn(HISTORY, output);
        assertEquals(GoalDecision.Action.BLOCK, first.action());
        assertEquals(1, goal.state().consecutiveBlocks());
        assertEquals(GoalDecision.Action.BLOCK, goal.afterTurn(HISTORY, output).action());
        GoalDecision third = goal.afterTurn(HISTORY, output);
        assertEquals(GoalDecision.Action.LIMIT, third.action(), "超过上限后交还用户");
        assertTrue(goal.active(), "达到上限不清除目标");
        assertEquals(3, goal.state().evaluations());
        assertEquals("还没运行测试 2", goal.state().lastReason());
        assertTrue(buffer.toString().contains("本轮已连续自动续跑 2 次仍未完成"), buffer.toString());
        goal.startTurn();
        assertEquals(0, goal.state().consecutiveBlocks(), "新一轮清零连续次数");
        assertEquals(3, goal.state().evaluations(), "判断次数保留");
    }

    @Test
    void continuationCarriesConditionAndReason() throws Exception {
        GoalController goal = controller(3);
        goal.set("mvn test 退出码为 0");
        replies.add(notOk("记录中没有测试结果"));
        GoalDecision decision = goal.afterTurn(HISTORY, output);
        String message = goal.continuation(decision);
        assertTrue(message.startsWith(GoalController.CONTINUATION_MARKER), message);
        assertTrue(message.contains("完成条件：mvn test 退出码为 0"), message);
        assertTrue(message.contains("判断理由：记录中没有测试结果"), message);
        assertThrows(IllegalStateException.class,
                () -> goal.continuation(new GoalDecision(GoalDecision.Action.LIMIT, "x")));
    }

    @Test
    void pendingWorkDefersWithoutCallingEvaluator() throws Exception {
        GoalController goal = controller(3);
        goal.set("构建完成");
        pending.set("后台任务 bg_1 仍在运行");
        GoalDecision decision = goal.afterTurn(HISTORY, output);
        assertEquals(GoalDecision.Action.DEFER, decision.action());
        assertEquals(0, evaluatorCalls);
        assertTrue(goal.active());
        assertEquals(0, goal.state().evaluations(), "暂缓不算一次判断");
        assertTrue(buffer.toString().contains("后台任务 bg_1 仍在运行，结果交回对话后再判断"), buffer.toString());
        pending.set(null);
        replies.add(ok("构建成功"));
        assertEquals(GoalDecision.Action.ACHIEVED, goal.afterTurn(HISTORY, output).action());
    }

    @Test
    void evaluatorFailureKeepsGoalAndStopsContinuation() throws Exception {
        GoalController goal = controller(3);
        goal.set("条件");
        replies.add("不是 JSON");
        GoalDecision decision = goal.afterTurn(HISTORY, output);
        assertEquals(GoalDecision.Action.ERROR, decision.action());
        assertTrue(goal.active(), "无法判断时不宣称成功，也不清除目标");
        assertEquals(1, goal.state().evaluations());
        assertEquals(0, goal.state().consecutiveBlocks(), "失败不计入连续未完成");
        assertTrue(buffer.toString().contains("判断器失败"), buffer.toString());
        assertTrue(buffer.toString().contains("停止自动续跑，目标保持活跃"), buffer.toString());

        GoalController timeout = new GoalController(new GoalEvaluator((m, t) -> {
            throw new HttpTimeoutException("timeout");
        }), 3, pending::get, clock);
        timeout.set("条件");
        GoalDecision timedOut = timeout.afterTurn(HISTORY, output);
        assertEquals(GoalDecision.Action.ERROR, timedOut.action());
        assertTrue(timedOut.reason().contains("HttpTimeoutException"), timedOut.reason());
    }

    @Test
    void interruptPropagates() {
        GoalController goal = new GoalController(new GoalEvaluator((m, t) -> {
            throw new InterruptedException();
        }), 3, pending::get, clock);
        goal.set("条件");
        assertThrows(InterruptedException.class, () -> goal.afterTurn(HISTORY, output));
        assertTrue(goal.active());
    }

    @Test
    void statusSetReplaceAndClear() throws Exception {
        GoalController goal = controller(5);
        assertTrue(goal.status().contains("当前没有活跃目标。用法：/goal <完成条件>"), goal.status());
        assertEquals("目标> 当前没有活跃目标。", goal.clear());
        goal.set("旧条件");
        replies.add(notOk("还差一步"));
        goal.afterTurn(HISTORY, output);
        clock.advance(Duration.ofSeconds(7));
        String status = goal.handle(new GoalCommand(GoalCommand.Kind.STATUS, null));
        assertTrue(status.contains("完成条件：旧条件"), status);
        assertTrue(status.contains("已判断 1 次，本轮连续未完成 1 次（上限 5），已用时 7 秒"), status);
        assertTrue(status.contains("最近判断：还差一步"), status);

        assertEquals("目标> 已替换旧目标（旧条件），按新条件开始：新条件", goal.set("新条件"));
        assertEquals(0, goal.state().evaluations(), "替换后计数清零");
        assertNull(goal.state().lastReason());
        assertTrue(goal.status().contains("最近判断：尚未判断"));
        assertEquals("目标> 已清除：新条件", goal.handle(new GoalCommand(GoalCommand.Kind.CLEAR, null)));
        assertFalse(goal.active());
        assertThrows(IllegalArgumentException.class,
                () -> goal.handle(new GoalCommand(GoalCommand.Kind.SET, "x")));
    }

    @Test
    void rejectsNonPositiveCap() {
        assertThrows(IllegalArgumentException.class, () -> controller(0));
    }

    @Test
    void pendingWorkReportsRunningBackgroundTasks() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BackgroundManager background = new BackgroundManager(new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("shell", "fake", new JSONObject());
            }

            @Override
            public String execute(String arguments) throws InterruptedException {
                release.await();
                return "退出码：0";
            }
        }, line -> { });
        Supplier<String> supplier = GoalStarter.pendingWork(background, null);
        assertNull(supplier.get(), "没有后台任务");
        background.start("sleep");
        assertEquals("后台任务 bg_1 仍在运行", supplier.get());
        release.countDown();
        long deadline = System.currentTimeMillis() + 5000;
        while (!"有后台结果尚未送达".equals(supplier.get()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("有后台结果尚未送达", supplier.get(), "结果送达对话之前仍然暂缓");
        background.takeNotifications();
        assertNull(supplier.get());
        background.shutdown();
    }

    private GoalController controller(int cap) {
        return new GoalController(new GoalEvaluator((messages, tools) -> {
            evaluatorCalls++;
            return new ChatMessage("assistant", replies.removeFirst());
        }), cap, pending::get, clock);
    }

    private static String ok(String reason) {
        return "{\"ok\":true,\"reason\":\"" + reason + "\",\"impossible\":false}";
    }

    private static String notOk(String reason) {
        return "{\"ok\":false,\"reason\":\"" + reason + "\",\"impossible\":false}";
    }

    /** 可推进的时钟，用于用时统计。 */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
