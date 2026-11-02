package dev.huagent.model;

import dev.huagent.tool.ToolDefinition;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
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

    /**
     * 同 complete(messages, tools, contextTokens)；流式请求时回答的文字边生成边交给 text，
     * 依次拼起来就是返回的 content（工具调用不经过 text）。在调用线程上交出。
     * 默认不流式、不调用 text：调用方在回答返回后再显示全文。
     */
    default ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools, IntConsumer contextTokens,
                                 Consumer<String> text) throws IOException, InterruptedException {
        return complete(messages, tools, contextTokens);
    }
}
