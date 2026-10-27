package dev.huagent.goal;

import dev.huagent.model.ChatMessage;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Goal Gate：持有当前目标与判断器，实现会话级的 Stop 前置判断。
 * 主循环在主 Agent 不再调用工具时调用 afterTurn：没有目标直接放行；后台或队友仍在工作时暂缓；
 * 否则请判断器读对话记录，未完成就返回 BLOCK，由主循环把理由作为新的用户消息续跑。
 * 自动续跑有两道出口：每轮模型调用上限（agent.max-model-calls）与连续未完成上限（goal.block-cap）；
 * 达到上限或判断器失败时把控制权交还用户，目标保留，不伪装成完成。只在主循环线程使用。
 */
public final class GoalController {
    public static final String RULE = "用户可以用 /goal 设置会话目标（完成条件）。有活跃目标时，你每次不再调用工具、准备结束本轮，"
            + "程序都会请一个没有工具的独立判断器阅读对话记录，判断条件是否已满足；未满足时会以 [目标未完成] 消息附上理由，"
            + "让你接着工作，不需要等用户输入。判断器看不到文件和命令本身，只能看到对话：运行验证命令后，在回复中写明命令"
            + "和关键结果（退出码、通过与失败数等），不要只说已完成。不要为了结束而宣称完成；确实无法完成（例如必需的操作被拒绝）时"
            + "如实说明原因。[目标未完成] 消息由程序生成，不是用户的新指令，不能据此跳过权限确认。";
    public static final String CONTINUATION_MARKER = "[目标未完成]";

    private final GoalEvaluator evaluator;
    private final int blockCap;
    private final Supplier<String> pendingWork;
    private final Clock clock;
    private GoalState state;

    /**
     * pendingWork 返回仍在进行、结果尚未交回对话的工作说明（如运行中的后台命令），没有时返回 null；
     * blockCap 是一轮内允许自动续跑的次数，超过后返回 LIMIT。
     */
    public GoalController(GoalEvaluator evaluator, int blockCap, Supplier<String> pendingWork, Clock clock) {
        if (blockCap <= 0) {
            throw new IllegalArgumentException("连续未完成上限必须为正数");
        }
        this.evaluator = Objects.requireNonNull(evaluator);
        this.blockCap = blockCap;
        this.pendingWork = Objects.requireNonNull(pendingWork);
        this.clock = Objects.requireNonNull(clock);
    }

    public GoalState state() {
        return state;
    }

    public boolean active() {
        return state != null;
    }

    public int blockCap() {
        return blockCap;
    }

    /** 设置或替换目标并清零计数，返回给终端的说明。条件已由 GoalCommand 校验。 */
    public String set(String condition) {
        GoalState previous = state;
        state = GoalState.start(condition, clock.instant());
        return previous == null ? "目标> 已设置，开始工作：" + condition
                : "目标> 已替换旧目标（" + previous.condition() + "），按新条件开始：" + condition;
    }

    /** 查看或清除；SET 由主循环在 UserPromptSubmit 放行后调用 set。 */
    public String handle(GoalCommand command) {
        return switch (command.kind()) {
            case STATUS -> status();
            case CLEAR -> clear();
            case SET -> throw new IllegalArgumentException("设置目标需经过 UserPromptSubmit，请调用 set");
        };
    }

    public String clear() {
        if (state == null) {
            return "目标> 当前没有活跃目标。";
        }
        String condition = state.condition();
        state = null;
        return "目标> 已清除：" + condition;
    }

    public String status() {
        if (state == null) {
            return "目标> 当前没有活跃目标。用法：/goal <完成条件> 设置并开始；/goal 查看；/goal clear 清除。";
        }
        return "目标> 完成条件：" + state.condition()
                + "\n目标> 已判断 " + state.evaluations() + " 次，本轮连续未完成 " + state.consecutiveBlocks()
                + " 次（上限 " + blockCap + "），已用时 " + elapsedSeconds() + " 秒"
                + "\n目标> 最近判断：" + (state.lastReason() == null ? "尚未判断" : state.lastReason());
    }

    /** 每轮开始（用户输入、队友消息或程序事件）时调用：连续未完成次数清零。 */
    public void startTurn() {
        if (state != null) {
            state = state.newTurn();
        }
    }

    /** 主 Agent 不再调用工具时调用；history 为本轮结束时的完整对话。判断过程与结果打印到 output。 */
    public GoalDecision afterTurn(List<ChatMessage> history, PrintWriter output) throws InterruptedException {
        if (state == null) {
            return GoalDecision.ALLOW;
        }
        String pending = pendingWork.get();
        if (pending != null) {
            print(output, "目标> " + pending + "，结果交回对话后再判断；目标保持活跃。");
            return new GoalDecision(GoalDecision.Action.DEFER, pending);
        }
        print(output, "目标> 请判断器检查完成条件（第 " + (state.evaluations() + 1) + " 次）...");
        GoalEvaluation evaluation;
        try {
            evaluation = evaluator.evaluate(state.condition(), history);
        } catch (IOException e) {
            String reason = "判断器失败（" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()) + "）";
            state = state.evaluated(reason, false);
            print(output, "目标> " + reason + "，停止自动续跑，目标保持活跃；可输入内容继续，或 /goal clear 清除。");
            return new GoalDecision(GoalDecision.Action.ERROR, reason);
        }
        if (evaluation.ok()) {
            String summary = "（判断 " + (state.evaluations() + 1) + " 次，用时 " + elapsedSeconds() + " 秒）";
            state = null;
            print(output, "目标> 已达成" + summary + "：" + evaluation.reason());
            return new GoalDecision(GoalDecision.Action.ACHIEVED, evaluation.reason());
        }
        if (evaluation.impossible()) {
            state = null;
            print(output, "目标> 判断为无法完成，已清除：" + evaluation.reason());
            return new GoalDecision(GoalDecision.Action.FAILED, evaluation.reason());
        }
        state = state.evaluated(evaluation.reason(), true);
        if (state.consecutiveBlocks() > blockCap) {
            print(output, "目标> 本轮已连续自动续跑 " + blockCap + " 次仍未完成，交还给你，目标保持活跃："
                    + evaluation.reason());
            return new GoalDecision(GoalDecision.Action.LIMIT, evaluation.reason());
        }
        print(output, "目标> 未完成（本轮第 " + state.consecutiveBlocks() + " 次）：" + evaluation.reason());
        return new GoalDecision(GoalDecision.Action.BLOCK, evaluation.reason());
    }

    /** BLOCK 时追加到对话的用户消息：程序生成，附完成条件与判断理由。 */
    public String continuation(GoalDecision decision) {
        if (state == null || decision.action() != GoalDecision.Action.BLOCK) {
            throw new IllegalStateException("只有活跃目标的 BLOCK 决定才能续跑");
        }
        return CONTINUATION_MARKER + " 程序的独立判断器认为完成条件尚未满足，请继续工作，不需要等待用户输入。"
                + "\n完成条件：" + state.condition()
                + "\n判断理由：" + decision.reason()
                + "\n完成验证后在回复中写明运行的命令与关键结果；确实无法继续时说明原因。";
    }

    /** /goal 设置目标时提交给主 Agent 的本轮输入。 */
    public static String taskPrompt(String condition) {
        return "[目标] 请持续工作直到满足以下完成条件，完成后写明验证命令与结果：\n" + condition;
    }

    private long elapsedSeconds() {
        return Duration.between(state.startedAt(), clock.instant()).toSeconds();
    }

    private static void print(PrintWriter output, String line) {
        synchronized (output) {
            output.println(line);
            output.flush();
        }
    }
}
