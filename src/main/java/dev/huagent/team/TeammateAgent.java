package dev.huagent.team;

import dev.huagent.compact.ContextCompactor;
import dev.huagent.harness.ToolTrace;
import dev.huagent.model.ChatMessage;
import dev.huagent.todo.TodoReminder;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;

import cn.hutool.core.util.StrUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 队友的 Agent 循环：与 ChatLoop 的一轮相同（Hook、模型请求、工具、提醒、压缩），区别是输入来自收件箱而不是终端，
 * 历史在多轮之间保留，一轮的最终回复作为返回值交给 Teammate 发给 Lead。
 */
final class TeammateAgent {
    /** 一轮的结果；completed 为 false 表示没有正常结束（超限、请求失败、被 Hook 拦截）。 */
    record Outcome(String text, boolean completed) {
    }

    private final TeammateSetup setup;
    private final List<ToolDefinition> definitions;
    private List<ChatMessage> history = new ArrayList<>();

    TeammateAgent(TeammateSetup setup) {
        this.setup = Objects.requireNonNull(setup);
        this.definitions = setup.tools().definitions();
        history.add(new ChatMessage("system", setup.systemPrompt()));
    }

    List<ChatMessage> history() {
        return List.copyOf(history);
    }

    Outcome turn(String input) throws InterruptedException {
        PrintWriter output = setup.output();
        String blocked = setup.hooks().userPromptSubmit(input, history);
        if (blocked != null) {
            say("钩子拒绝：" + blocked + " 本轮输入未记录。");
            return new Outcome("钩子拒绝了本轮输入：" + blocked, false);
        }
        List<ChatMessage> request = new ArrayList<>(history);
        request.add(new ChatMessage("user", input));
        int turnStart = request.size() - 1;
        TodoReminder reminder = setup.reminder();
        if (reminder != null) {
            reminder.startTurn();
        }
        try {
            for (int step = 1; step <= setup.maxModelCalls(); step++) {
                if (setup.debug()) {
                    say("模型请求> 第 " + step + " 次请求中...");
                }
                List<ChatMessage> sent = request;
                ContextCompactor compactor = setup.compactor();
                if (compactor != null) {
                    ContextCompactor.Prepared prepared = compactor.beforeRequest(List.copyOf(request), output);
                    if (prepared.summarized()) {
                        request = new ArrayList<>(prepared.history());
                        turnStart = request.size() - 1;
                    }
                    sent = prepared.sent();
                }
                ChatMessage answer = setup.client().complete(List.copyOf(sent), definitions);
                request.add(answer);
                if (StrUtil.isNotBlank(answer.content())) {
                    say("助手> " + answer.content());
                }
                if (answer.toolCalls().isEmpty()) {
                    history = request;
                    setup.hooks().stop(request.subList(turnStart, request.size()));
                    String text = StrUtil.isBlank(answer.content()) ? "（本轮没有文字回复）" : answer.content().strip();
                    return new Outcome(text, true);
                }
                boolean limitReached = step == setup.maxModelCalls();
                for (ToolCall call : answer.toolCalls()) {
                    say(ToolTrace.request(call, setup.debug()));
                    String result = limitReached ? "工具未执行：已达到本轮模型调用上限。" : setup.tools().execute(call);
                    request.add(ChatMessage.tool(call.id(), result));
                    say(ToolTrace.result(call, result, setup.debug()));
                }
                String nudge = reminder == null || limitReached ? null : reminder.afterToolBatch();
                if (nudge != null) {
                    int last = request.size() - 1;
                    ChatMessage tool = request.get(last);
                    request.set(last, ChatMessage.tool(tool.toolCallId(), tool.content() + "\n\n" + nudge));
                }
                history = request;
                if (limitReached) {
                    String reason = "已达到本轮模型调用上限（" + setup.maxModelCalls() + " 次），停止本轮；最后一批工具未执行。";
                    request.add(new ChatMessage("assistant", reason));
                    say("助手> " + reason);
                    return new Outcome(reason, false);
                }
            }
        } catch (HttpTimeoutException e) {
            return failed(request, "模型请求超时");
        } catch (IOException e) {
            return failed(request, "模型请求失败：" + e.getMessage());
        }
        throw new IllegalStateException("unreachable");
    }

    /** 队友的输入来自收件箱，丢弃就再也拿不回来；失败时保留本轮已有的消息（以 user 或 tool 结尾，协议仍合法）。 */
    private Outcome failed(List<ChatMessage> request, String reason) {
        history = request;
        say(reason + "；本轮输入已保留在历史中。");
        return new Outcome(reason + "。本轮输入已保留，可发消息让我重试。", false);
    }

    private void say(String line) {
        setup.output().println(line);
        setup.output().flush();
    }
}
