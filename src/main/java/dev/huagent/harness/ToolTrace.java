package dev.huagent.harness;

import dev.huagent.tool.ToolCall;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;

/**
 * 工具调用在终端上的显示，主 Agent、子代理与队友共用。只影响终端，模型收到的始终是完整结果。
 * debug 模式（教程各入口与 hu --debug）原样显示调用 ID、完整参数与完整结果；
 * 非 debug 模式（hu 默认）每次调用只占两行：工具名加主要参数，下一行缩进显示结果的第一行与总行数。
 * 需要确认的调用仍由权限确认显示完整参数。
 */
public final class ToolTrace {
    public static final int ARGUMENT_CHARS = 80;
    public static final int RESULT_CHARS = 100;

    private ToolTrace() {
    }

    public static String request(ToolCall call, boolean debug) {
        if (debug) {
            return "工具请求> " + call.name() + " [" + call.id() + "] " + call.arguments();
        }
        String summary = summary(call.arguments());
        return "工具> " + call.name() + (summary.isEmpty() ? "" : " " + summary);
    }

    public static String result(ToolCall call, String result, boolean debug) {
        return debug ? "工具结果> [" + call.id() + "] " + result : "  " + preview(result);
    }

    /**
     * 参数里第一个非空字符串（各工具 schema 都把主要参数放在最前，如 path、command、name）的第一行；
     * 参数不是 JSON 对象时显示原文开头，没有字符串参数时不显示。
     */
    static String summary(String arguments) {
        Object parsed;
        try {
            parsed = JSON.parse(arguments);
        } catch (RuntimeException e) {
            return firstLine(arguments, ARGUMENT_CHARS);
        }
        if (!(parsed instanceof JSONObject object)) {
            return firstLine(arguments, ARGUMENT_CHARS);
        }
        for (Object value : object.values()) {
            if (value instanceof String text && StrUtil.isNotBlank(text)) {
                return firstLine(text, ARGUMENT_CHARS);
            }
        }
        return "";
    }

    /** 第一行且不超过字符上限；多行时注明总行数。 */
    static String preview(String result) {
        List<String> lines = result.strip().lines().toList();
        if (lines.isEmpty()) {
            return "（无输出）";
        }
        String head = cut(lines.get(0).strip(), RESULT_CHARS);
        return lines.size() == 1 ? head : head + " …（共 " + lines.size() + " 行）";
    }

    private static String firstLine(String text, int limit) {
        List<String> lines = text.strip().lines().toList();
        if (lines.isEmpty()) {
            return "";
        }
        String head = cut(lines.get(0).strip(), limit);
        return lines.size() > 1 && !head.endsWith("…") ? head + " …" : head;
    }

    private static String cut(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        // 不在代理对中间截断。
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end) + "…";
    }
}
