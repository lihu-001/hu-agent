package dev.huagent.goal;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个活跃目标的状态：完成条件、开始时间、判断次数、本轮连续未完成次数与最近一次判断理由。
 * 只在会话内存中保存，不跨会话；每次变化返回新实例。
 */
public record GoalState(String condition, Instant startedAt, int evaluations, int consecutiveBlocks,
                        String lastReason) {

    public GoalState {
        Objects.requireNonNull(condition);
        Objects.requireNonNull(startedAt);
        if (evaluations < 0 || consecutiveBlocks < 0) {
            throw new IllegalArgumentException("计数不能为负");
        }
    }

    public static GoalState start(String condition, Instant now) {
        return new GoalState(condition, now, 0, 0, null);
    }

    /** 记录一次判断；blocked 为 true 时连续未完成次数加一。 */
    public GoalState evaluated(String reason, boolean blocked) {
        return new GoalState(condition, startedAt, evaluations + 1, blocked ? consecutiveBlocks + 1 : 0, reason);
    }

    /** 新一轮开始时清零连续未完成次数，判断次数与理由保留。 */
    public GoalState newTurn() {
        return consecutiveBlocks == 0 ? this
                : new GoalState(condition, startedAt, evaluations, 0, lastReason);
    }
}
