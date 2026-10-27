package dev.huagent.model;

import dev.huagent.tool.ToolCall;

import java.util.List;

public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
    public ChatMessage {
        toolCalls = List.copyOf(toolCalls);
    }

    public ChatMessage(String role, String content) {
        this(role, content, List.of(), null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> calls) {
        return new ChatMessage("assistant", content, calls, null);
    }

    public static ChatMessage tool(String callId, String output) {
        return new ChatMessage("tool", output, List.of(), callId);
    }
}
