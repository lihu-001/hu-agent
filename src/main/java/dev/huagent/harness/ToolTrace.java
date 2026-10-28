package dev.huagent.harness;

import dev.huagent.tool.ToolCall;

/**
 * 工具调用在终端上的显示，主 Agent、子代理与队友共用。只影响终端，模型收到的始终是完整结果。
 * 只在 debug 模式（教程各入口与 hu --debug）显示，原样显示调用 ID、完整参数与完整结果；
 * 非 debug 模式（hu 默认）不显示工具调用，只显示最终回答。需要确认的调用仍由权限确认显示完整参数。
 */
public final class ToolTrace {
    private ToolTrace() {
    }

    public static String request(ToolCall call) {
        return "工具请求> " + call.name() + " [" + call.id() + "] " + call.arguments();
    }

    public static String result(ToolCall call, String result) {
        return "工具结果> [" + call.id() + "] " + result;
    }
}
