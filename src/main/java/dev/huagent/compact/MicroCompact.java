package dev.huagent.compact;

import dev.huagent.model.ChatMessage;
import dev.huagent.tool.ToolCall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第一层压缩：只保留最近几个工具结果的原文，更早且较长的工具结果换成一行占位说明。
 * 不请求模型，返回新列表、不修改入参；只作用于发给模型的请求，内存历史仍保留原文。
 */
public final class MicroCompact {
    public static final int MIN_COMPACT_CHARS = 200;
    public static final String PLACEHOLDER_PREFIX = "[已压缩] ";
    /** 这些工具的结果本身就是总结或说明，重新获取代价高或会改变状态，不做替换。 */
    public static final Set<String> EXEMPT_TOOLS = Set.of("task", "load_skill", "todo_write", "compact");

    private MicroCompact() {
    }

    public static List<ChatMessage> apply(List<ChatMessage> messages, int keepRecent) {
        Map<String, String> toolNames = new HashMap<>();
        int toolResults = 0;
        for (ChatMessage message : messages) {
            for (ToolCall call : message.toolCalls()) {
                toolNames.put(call.id(), call.name());
            }
            if ("tool".equals(message.role())) {
                toolResults++;
            }
        }
        int compactable = toolResults - keepRecent;
        List<ChatMessage> result = new ArrayList<>(messages.size());
        int seen = 0;
        for (ChatMessage message : messages) {
            if ("tool".equals(message.role()) && seen++ < compactable) {
                String name = toolNames.getOrDefault(message.toolCallId(), "未知");
                String content = message.content() == null ? "" : message.content();
                if (content.length() > MIN_COMPACT_CHARS && !EXEMPT_TOOLS.contains(name)) {
                    message = ChatMessage.tool(message.toolCallId(), PLACEHOLDER_PREFIX + "较早的 " + name
                            + " 工具结果（原 " + content.length() + " 字符）已从上下文移除，需要时请重新调用工具获取。");
                }
            }
            result.add(message);
        }
        return result;
    }

    /** 粗略的上下文规模：消息正文、工具名称与参数的字符数之和，不是服务商的 token 计数。 */
    public static int size(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage message : messages) {
            total += message.content() == null ? 0 : message.content().length();
            for (ToolCall call : message.toolCalls()) {
                total += call.name().length() + (call.arguments() == null ? 0 : call.arguments().length());
            }
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }
}
