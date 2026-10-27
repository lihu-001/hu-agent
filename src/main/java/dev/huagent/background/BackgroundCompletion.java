package dev.huagent.background;

import java.util.Objects;

/**
 * S12：后台任务进入终态时交给监听器的快照。source 是启动时附带的发起者标识（定时计划 ID），
 * 普通 background_run 为 null；与对话通知不同，取消也会产生回调，便于发起者把结果写回自己的记录。
 */
public record BackgroundCompletion(String id, String source, BackgroundStatus status, String result,
                                   long elapsedMillis) {
    public BackgroundCompletion {
        Objects.requireNonNull(id);
        Objects.requireNonNull(status);
        result = result == null ? "" : result;
    }
}
