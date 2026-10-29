package dev.huagent.subagent;

import dev.huagent.harness.ToolTrace;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.tool.ToolRegistry;

import cn.hutool.core.util.StrUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 子代理的独立循环：每次运行从全新的历史开始（系统提示词 + 任务），结束后历史直接丢弃，只返回最终文字。
 * 工具注册表由调用方传入，不包含 task，因此子代理不能再派生子代理。
 */
public final class SubagentRunner {
    private final ModelClient client;
    private final ToolRegistry tools;
    private final int maxModelCalls;
    private final String systemPrompt;
    private final PrintWriter output;
    /** false 时（hu 默认）不显示模型请求、工具调用与完成统计。 */
    private final boolean debug;

    public SubagentRunner(ModelClient client, ToolRegistry tools, int maxModelCalls,
                          String systemPrompt, PrintWriter output) {
        this(client, tools, maxModelCalls, systemPrompt, output, true);
    }

    public SubagentRunner(ModelClient client, ToolRegistry tools, int maxModelCalls,
                          String systemPrompt, PrintWriter output, boolean debug) {
        if (maxModelCalls <= 0) {
            throw new IllegalArgumentException("子代理模型调用上限必须为正数");
        }
        this.client = Objects.requireNonNull(client);
        this.tools = Objects.requireNonNull(tools);
        this.maxModelCalls = maxModelCalls;
        this.systemPrompt = Objects.requireNonNull(systemPrompt);
        this.output = Objects.requireNonNull(output);
        this.debug = debug;
    }

    public List<ToolDefinition> definitions() {
        return tools.definitions();
    }

    /** 返回子代理最终回复；达到上限时返回说明。模型请求失败抛出 IOException，由 TaskTool 转为工具结果。 */
    public Result run(String description, String prompt) throws IOException, InterruptedException {
        // 关键点：不复制父代理历史，子代理只看到系统提示词和本次任务。
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));
        messages.add(new ChatMessage("user", prompt));
        List<ToolDefinition> definitions = tools.definitions();
        String label = "  子代理[" + description + "]";
        int toolCalls = 0;
        for (int step = 1; step <= maxModelCalls; step++) {
            if (step == maxModelCalls && step > 1) {
                // 与主循环相同：最后一次请求前说明工具不会再执行，让子代理把已有发现总结出来。
                int last = messages.size() - 1;
                ChatMessage tool = messages.get(last);
                messages.set(last, ChatMessage.tool(tool.toolCallId(), tool.content() + "\n\n[程序提示] 这是最后一次"
                        + "请求（模型调用上限 " + maxModelCalls + " 次），再调用工具也不会执行。请直接用文字总结已完成的工作、"
                        + "发现和未完成的部分。"));
            }
            detail(label + " 模型请求> 第 " + step + " 次请求中...");
            ChatMessage answer = client.complete(List.copyOf(messages), definitions);
            messages.add(answer);
            if (answer.toolCalls().isEmpty()) {
                String text = StrUtil.isBlank(answer.content()) ? "子代理结束，但没有返回文字。" : answer.content().strip();
                detail(label + " 完成> 模型请求 " + step + " 次，工具调用 " + toolCalls + " 个");
                return new Result(text, step, toolCalls, true);
            }
            if (step == maxModelCalls) {
                // 最后一批工具不执行；历史随即丢弃，因此不必补齐 tool 消息。
                break;
            }
            for (ToolCall call : answer.toolCalls()) {
                detail(label + " " + ToolTrace.request(call));
                String result = tools.execute(call);
                toolCalls++;
                messages.add(ChatMessage.tool(call.id(), result));
                detail(label + " " + ToolTrace.result(call, result));
            }
        }
        print(label + " 停止> 已达到子代理模型调用上限（" + maxModelCalls + " 次）");
        String partial = lastAssistantText(messages);
        String text = "子代理未完成：已达到模型调用上限（" + maxModelCalls + " 次），最后一批工具未执行。"
                + (partial == null ? "" : "最后的说明：" + partial);
        return new Result(text, maxModelCalls, toolCalls, false);
    }

    private static String lastAssistantText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if ("assistant".equals(message.role()) && StrUtil.isNotBlank(message.content())) {
                return message.content().strip();
            }
        }
        return null;
    }

    private void print(String line) {
        output.println(line);
        output.flush();
    }

    /** 只在 debug 模式显示的运行细节。 */
    private void detail(String line) {
        if (debug) {
            print(line);
        }
    }

    /** completed 为 false 表示因调用上限停止。 */
    public record Result(String text, int modelCalls, int toolCalls, boolean completed) {
    }
}
