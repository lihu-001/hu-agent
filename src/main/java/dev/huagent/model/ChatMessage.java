package dev.huagent.model;

import dev.huagent.tool.ToolCall;

import java.util.List;

/** images 只出现在用户消息里（hu 粘贴或拖入的图片，见 InputImages），其余消息为空列表。 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId,
                          List<ChatImage> images) {
    public ChatMessage {
        toolCalls = List.copyOf(toolCalls);
        images = images == null ? List.of() : List.copyOf(images);
    }

    public ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, List.of());
    }

    public ChatMessage(String role, String content) {
        this(role, content, List.of(), null);
    }

    public static ChatMessage user(String content, List<ChatImage> images) {
        return new ChatMessage("user", content, List.of(), null, images);
    }

    public static ChatMessage assistant(String content, List<ToolCall> calls) {
        return new ChatMessage("assistant", content, calls, null);
    }

    public static ChatMessage tool(String callId, String output) {
        return new ChatMessage("tool", output, List.of(), callId);
    }

    /** 换掉正文，角色、工具调用与图片不变（附加程序提示时用，不能丢掉用户粘贴的图片）。 */
    public ChatMessage withContent(String content) {
        return new ChatMessage(role, content, toolCalls, toolCallId, images);
    }
}
