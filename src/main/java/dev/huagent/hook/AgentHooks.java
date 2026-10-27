package dev.huagent.hook;

import dev.huagent.model.ChatMessage;
import dev.huagent.tool.ToolCall;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 同一条事件链供 ChatLoop 和 ToolRegistry 共用；前置短路，后置异常隔离。 */
public final class AgentHooks {
    private final List<AgentHook> hooks;
    private final Consumer<String> warnings;

    public AgentHooks(List<? extends AgentHook> hooks, Consumer<String> warnings) {
        this.hooks = List.copyOf(hooks);
        this.warnings = Objects.requireNonNull(warnings);
    }

    public static AgentHooks empty() {
        return new AgentHooks(List.of(), message -> { });
    }

    /** S18：按执行顺序列出每个回调的说明，供 /hooks 查看。 */
    public List<String> descriptions() {
        return hooks.stream().map(AgentHook::describe).toList();
    }

    public String userPromptSubmit(String prompt, List<ChatMessage> history) throws InterruptedException {
        List<ChatMessage> snapshot = List.copyOf(history);
        return before("UserPromptSubmit", hook -> hook.userPromptSubmit(prompt, snapshot));
    }

    public String preToolUse(ToolCall call) throws InterruptedException {
        return before("PreToolUse", hook -> hook.preToolUse(call));
    }

    public void postToolUse(ToolCall call, String result, long elapsedNanos) {
        after("PostToolUse [" + call.id() + "]", hook -> hook.postToolUse(call, result, elapsedNanos));
    }

    public void stop(List<ChatMessage> turn) {
        List<ChatMessage> snapshot = List.copyOf(turn);
        after("Stop", hook -> hook.stop(snapshot));
    }

    private String before(String event, Before callback) throws InterruptedException {
        for (AgentHook hook : hooks) {
            try {
                String reason = callback.run(hook);
                if (reason != null) {
                    return reason;
                }
            } catch (IOException | RuntimeException e) {
                return event + " 前置检查失败（" + e.getClass().getSimpleName() + "）。";
            }
        }
        return null;
    }

    private void after(String event, After callback) {
        for (AgentHook hook : hooks) {
            try {
                callback.run(hook);
            } catch (IOException | RuntimeException e) {
                try {
                    warnings.accept("钩子警告> " + event + " 后置回调失败（"
                            + e.getClass().getSimpleName() + "），保留原结果。");
                } catch (RuntimeException ignored) {
                    // 警告输出故障也不能覆盖已产生的工具结果或最终回复。
                }
            }
        }
    }

    private interface Before {
        String run(AgentHook hook) throws IOException, InterruptedException;
    }

    private interface After {
        void run(AgentHook hook) throws IOException;
    }
}
