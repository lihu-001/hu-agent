package dev.huagent.goal;

import dev.huagent.background.BackgroundManager;
import dev.huagent.background.BackgroundStatus;
import dev.huagent.background.BackgroundTask;
import dev.huagent.model.ModelClient;
import dev.huagent.team.TeamManager;
import dev.huagent.team.Teammate;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 组装点回调：后台管理器（团队模式还有队友）就绪后创建主 Agent 的 Goal Gate。
 * null 表示本课机制关闭（上一课入口），组装行为与 S16 完全相同。
 */
@FunctionalInterface
public interface GoalStarter {

    /** pendingWork 见 GoalController；返回的控制器交给主 Agent（团队模式为 Lead）的 ChatLoop。 */
    GoalController bind(Supplier<String> pendingWork);

    /** evaluatorClient 可以是与主 Agent 不同模型的客户端；blockCap 对应 goal.block-cap。 */
    static GoalStarter of(ModelClient evaluatorClient, int blockCap) {
        return of(evaluatorClient, blockCap, Clock.systemDefaultZone());
    }

    /** 显式时钟，供测试使用。 */
    static GoalStarter of(ModelClient evaluatorClient, int blockCap, Clock clock) {
        GoalEvaluator evaluator = new GoalEvaluator(evaluatorClient);
        Objects.requireNonNull(clock);
        return pendingWork -> new GoalController(evaluator, blockCap, pendingWork, clock);
    }

    /**
     * 结果尚未交回对话的工作：运行中的手动后台命令、已完成但还没送达的后台通知、正在工作的队友。
     * 定时计划的运行不算（它们按 cron 无限重复，结果也不进入对话）。team 为 null 表示单 Agent。
     */
    static Supplier<String> pendingWork(BackgroundManager background, TeamManager team) {
        Objects.requireNonNull(background);
        return () -> {
            List<String> running = background.list().stream()
                    .filter(task -> task.status() == BackgroundStatus.RUNNING)
                    .map(BackgroundTask::id)
                    .toList();
            if (!running.isEmpty()) {
                return "后台任务 " + String.join("、", running) + " 仍在运行";
            }
            if (background.hasQueuedNotifications()) {
                return "有后台结果尚未送达";
            }
            if (team != null) {
                List<String> working = team.names().stream()
                        .map(team::member)
                        .filter(member -> member != null && member.status() == Teammate.Status.WORKING)
                        .map(Teammate::name)
                        .toList();
                if (!working.isEmpty()) {
                    return "队友 " + String.join("、", working) + " 仍在工作";
                }
            }
            return null;
        };
    }
}
