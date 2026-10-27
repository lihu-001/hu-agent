package dev.huagent.goal;

import java.util.Objects;

/**
 * 主 Agent 不再调用工具时 Goal Gate 的决定。只有 BLOCK 让主循环续跑，其余都结束本轮并触发 Stop。
 * ALLOW：没有活跃目标；DEFER：后台或队友还在工作，暂不判断；ACHIEVED / FAILED：判断后清除目标；
 * BLOCK：未完成，把理由写回对话继续；LIMIT：连续未完成次数达到上限；ERROR：判断器失败。后两者保留目标。
 */
public record GoalDecision(Action action, String reason) {

    public enum Action {
        ALLOW, DEFER, ACHIEVED, FAILED, BLOCK, LIMIT, ERROR
    }

    public static final GoalDecision ALLOW = new GoalDecision(Action.ALLOW, null);

    public GoalDecision {
        Objects.requireNonNull(action);
    }
}
