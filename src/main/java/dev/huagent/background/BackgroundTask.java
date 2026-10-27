package dev.huagent.background;

/** 后台任务的只读快照；result 为 Shell 工具的完整返回文本，运行中为 null。 */
public record BackgroundTask(String id, String command, BackgroundStatus status, String result,
                             long elapsedMillis) {
}
