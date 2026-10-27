package dev.huagent.hook;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import dev.huagent.model.ChatMessage;
import dev.huagent.tool.ToolCall;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 一个配置命令对应一个回调，沿用 AgentHooks 的前置短路和后置异常隔离。 */
final class CommandHook implements AgentHook {
    private final String event;
    private final Pattern matcher;
    private final String command;
    private final Path cwd;
    private final Duration timeout;
    private final Consumer<String> output;
    private final HookTrustSnapshot trust;
    private final HookCommandRunner runner = new HookCommandRunner();

    CommandHook(String event, Pattern matcher, String command, Path cwd,
                Duration timeout, Consumer<String> output) {
        this(event, matcher, command, cwd, timeout, output, null);
    }

    CommandHook(String event, Pattern matcher, String command, Path cwd,
                Duration timeout, Consumer<String> output, HookTrustSnapshot trust) {
        this.event = event;
        this.matcher = matcher;
        this.command = command;
        this.cwd = cwd;
        this.timeout = timeout;
        this.output = output;
        this.trust = trust;
    }

    /** S18：事件、工具匹配（只有工具事件有）、命令与超时；命令中的控制字符转义显示。 */
    @Override
    public String describe() {
        boolean toolEvent = event.equals("PreToolUse") || event.equals("PostToolUse");
        StringBuilder text = new StringBuilder("用户命令 ").append(event);
        if (toolEvent) {
            text.append(" matcher=").append(matcher.pattern());
        }
        text.append("：");
        command.codePoints().forEach(code -> {
            if (Character.isISOControl(code)) {
                text.append(String.format("\\u%04x", code));
            } else {
                text.appendCodePoint(code);
            }
        });
        return text.append("（超时 ").append(timeout.toSeconds()).append(" 秒）").toString();
    }

    @Override
    public String userPromptSubmit(String prompt, List<ChatMessage> history)
            throws IOException, InterruptedException {
        if (!event.equals("UserPromptSubmit")) {
            return null;
        }
        JSONObject input = input();
        input.put("prompt", prompt);
        input.put("history", messages(history));
        return before(input);
    }

    @Override
    public String preToolUse(ToolCall call) throws IOException, InterruptedException {
        return matches("PreToolUse", call) ? before(toolInput(call)) : null;
    }

    @Override
    public void postToolUse(ToolCall call, String result, long elapsedNanos) throws IOException {
        if (matches("PostToolUse", call)) {
            JSONObject input = toolInput(call);
            input.put("tool_response", result);
            input.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
            after(input);
        }
    }

    @Override
    public void stop(List<ChatMessage> turn) throws IOException {
        if (event.equals("Stop")) {
            JSONObject input = input();
            input.put("turn", messages(turn));
            after(input);
        }
    }

    private boolean matches(String expected, ToolCall call) {
        return event.equals(expected) && matcher.matcher(call.name()).matches();
    }

    private JSONObject input() {
        JSONObject input = new JSONObject();
        input.put("hook_event_name", event);
        input.put("cwd", cwd.toString());
        return input;
    }

    private JSONObject toolInput(ToolCall call) {
        JSONObject input = input();
        input.put("tool_name", call.name());
        input.put("tool_call_id", call.id());
        input.put("tool_arguments", call.arguments());
        Object arguments;
        try {
            arguments = JSON.parse(call.arguments());
        } catch (RuntimeException e) {
            arguments = call.arguments();
        }
        input.put("tool_input", arguments);
        return input;
    }

    private List<JSONObject> messages(List<ChatMessage> messages) {
        return messages.stream().map(message -> {
            JSONObject value = new JSONObject();
            value.put("role", message.role());
            value.put("content", message.content());
            value.put("tool_call_id", message.toolCallId());
            value.put("tool_calls", message.toolCalls().stream().map(call -> {
                JSONObject tool = new JSONObject();
                tool.put("id", call.id());
                tool.put("name", call.name());
                tool.put("arguments", call.arguments());
                return tool;
            }).toList());
            return value;
        }).toList();
    }

    private String before(JSONObject input) throws IOException, InterruptedException {
        HookCommandRunner.Result result = execute(input);
        if (result.exitCode() != 0) {
            return event + " 命令" + (result.exitCode() == 2 ? "拒绝" : "失败")
                    + "（退出码 " + result.exitCode() + "）"
                    + (result.output().isBlank() ? "" : "：" + result.output().strip());
        }
        log(result);
        return null;
    }

    private void after(JSONObject input) throws IOException {
        try {
            HookCommandRunner.Result result = execute(input);
            log(result);
            if (result.exitCode() != 0) {
                throw new IOException("Hook 命令失败");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Hook 命令中断", e);
        }
    }

    private void log(HookCommandRunner.Result result) {
        if (!result.output().isBlank() || result.exitCode() != 0) {
            output.accept("钩子命令> " + event + "（退出码 " + result.exitCode() + "）"
                    + (result.output().isBlank() ? "" : "\n" + result.output().strip()));
        }
    }

    private HookCommandRunner.Result execute(JSONObject input) throws IOException, InterruptedException {
        if (trust != null) {
            try {
                trust.verify();
            } catch (IOException e) {
                output.accept("Hook 信任> 配置或脚本已变化/不可读取，用户 Hook 已停止，请重启后重新确认。");
                throw e;
            }
        }
        return runner.run(command, cwd, input.toJSONString(), timeout);
    }
}
