package dev.huagent.hook;

import dev.huagent.model.ChatMessage;
import dev.huagent.tool.ToolCall;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class LoggingHook implements AgentHook {
    private final PrintWriter output;

    public LoggingHook(PrintWriter output) {
        this.output = Objects.requireNonNull(output);
    }

    @Override
    public String userPromptSubmit(String prompt, List<ChatMessage> history) {
        output.println("钩子 UserPromptSubmit> 提示词 " + prompt.length()
                + " 字符，记录前历史 " + history.size() + " 条");
        output.flush();
        return null;
    }

    @Override
    public String preToolUse(ToolCall call) {
        output.println("钩子 PreToolUse> " + call.name() + " [" + call.id() + "]");
        output.flush();
        return null;
    }

    @Override
    public void postToolUse(ToolCall call, String result, long elapsedNanos) {
        output.println("钩子 PostToolUse> " + call.name() + " [" + call.id() + "] "
                + TimeUnit.NANOSECONDS.toMillis(elapsedNanos) + " ms，输出 " + result.length() + " 字符");
        output.flush();
    }

    @Override
    public void stop(List<ChatMessage> turn) {
        long toolResults = turn.stream().filter(message -> "tool".equals(message.role())).count();
        output.println("钩子 Stop> 本轮回复完成，工具结果 " + toolResults + " 条");
        output.flush();
    }

    @Override
    public String describe() {
        return "内置日志 LoggingHook（四个事件）";
    }
}
