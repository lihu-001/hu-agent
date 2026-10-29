package dev.huagent.harness;

import dev.huagent.prompt.FramedPrompt;
import dev.huagent.prompt.TerminalText;
import dev.huagent.tool.ToolCall;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 工具调用在终端上的显示，主 Agent、子代理与队友共用。只影响终端，模型收到的始终是完整结果。
 * debug 模式（教程各入口与 hu --debug）原样显示调用 ID、完整参数与完整结果（request、result）；
 * 非 debug 模式（hu 默认）与 Claude Code 一样每次调用显示摘要：调用了什么（subject）、结果如何（outcome），
 * 由 Transcript 排成一行（summary）或两行。需要确认的调用由权限确认显示完整参数，摘要里不再重复。
 */
public final class ToolTrace {
    /** 摘要里参数与结果各自最多占的终端列数。 */
    static final int MAX_COLUMNS = 80;
    private static final List<String> FAILURES = List.of("工具错误", "权限拒绝", "钩子拒绝", "工具未执行", "工具已中断");
    private static final Pattern NUMBERED_LINE = Pattern.compile("^ *\\d+\\t.*");

    private ToolTrace() {
    }

    public static String request(ToolCall call) {
        return "工具请求> " + call.name() + " [" + call.id() + "] " + call.arguments();
    }

    public static String result(ToolCall call, String result) {
        return "工具结果> [" + call.id() + "] " + result;
    }

    /** 一行摘要，如 "工具> grep 上下文剩余 · 找到 1 个文件"、"工具> shell · 退出码 0，12 行输出"。 */
    public static String summary(ToolCall call, String result) {
        String subject = subject(call);
        return "工具> " + call.name() + (subject.isEmpty() ? "" : " " + subject) + " · " + outcome(call.name(), result);
    }

    /** 免确认的只读工具显示路径或模式；其余工具的参数已经在确认行里显示过（或不值得显示），返回空串。 */
    static String subject(ToolCall call) {
        JSONObject args;
        try {
            args = JSON.parseObject(call.arguments());
        } catch (RuntimeException e) {
            return "";
        }
        if (args == null) {
            return "";
        }
        String text = switch (call.name()) {
            case "read_file" -> args.getString("path")
                    + (args.get("offset") instanceof Integer offset && offset > 1 ? " 第 " + offset + " 行起" : "");
            case "glob" -> args.getString("pattern");
            case "grep" -> args.getString("pattern") + scope(args);
            default -> "";
        };
        return text == null ? "" : clip(text);
    }

    private static String scope(JSONObject args) {
        String path = args.getString("path");
        String glob = args.getString("glob");
        if (path == null && glob == null) {
            return "";
        }
        return "（" + (path == null ? glob : glob == null ? path : path + "，" + glob) + "）";
    }

    /** 工具没有正常完成：工具错误、权限或钩子拒绝、未执行、被中断。 */
    static boolean failed(String result) {
        return result != null && FAILURES.stream().anyMatch(result::startsWith);
    }

    static String outcome(String name, String result) {
        if (result == null || result.isBlank()) {
            return "无输出";
        }
        String first = result.lines().findFirst().orElse("");
        if (failed(result)) {
            return clip(first);
        }
        return switch (name) {
            case "shell" -> shell(result, first);
            case "read_file" -> "[空文件]".equals(result) ? "空文件"
                    : result.lines().filter(line -> NUMBERED_LINE.matcher(line).matches()).count() + " 行"
                    + (result.contains("继续读取]") ? "（未读完）" : "");
            case "glob" -> "[无匹配项]".equals(result) ? "无匹配"
                    : result.lines().filter(line -> !line.startsWith("[")).count() + " 项";
            case "grep" -> first.startsWith("[无匹配]") ? "无匹配" : clip(first);
            default -> clip(first);
        };
    }

    /** ShellTool 的结果第一行是 "退出码：N"，之后是输出。 */
    private static String shell(String result, String first) {
        if (!first.startsWith("退出码：")) {
            return clip(first);
        }
        long lines = result.lines().skip(1).filter(line -> !line.isBlank() && !line.startsWith("[输出")).count();
        return "退出码 " + first.substring("退出码：".length()).strip()
                + (lines == 0 ? "，无输出" : "，" + lines + " 行输出")
                + (result.contains("[输出已截断") ? "（已截断）" : "");
    }

    /** 转义控制字符，超过 MAX_COLUMNS 列时截断加省略号。 */
    private static String clip(String text) {
        String visible = TerminalText.visible(text.strip());
        if (FramedPrompt.displayWidth(visible) <= MAX_COLUMNS) {
            return visible;
        }
        StringBuilder out = new StringBuilder();
        int width = 0;
        for (int i = 0; i < visible.length(); ) {
            int codePoint = visible.codePointAt(i);
            String ch = Character.toString(codePoint);
            width += FramedPrompt.displayWidth(ch);
            if (width > MAX_COLUMNS - 1) {
                break;
            }
            out.append(ch);
            i += Character.charCount(codePoint);
        }
        return out + "…";
    }
}
