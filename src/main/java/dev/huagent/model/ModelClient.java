package dev.huagent.model;

import dev.huagent.tool.ToolDefinition;

import java.io.IOException;
import java.util.List;
import java.util.function.IntConsumer;

@FunctionalInterface
public interface ModelClient {
    ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools)
            throws IOException, InterruptedException;

    /**
     * 同 complete；响应带 usage 时，把本次请求之后对话占用的上下文 token 数交给 contextTokens。
     * 只有主对话的 ChatLoop 调用它来显示用量，子代理、总结与判断器的请求不经过这里。默认不报告。
     */
    default ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools, IntConsumer contextTokens)
            throws IOException, InterruptedException {
        return complete(messages, tools);
    }
}
