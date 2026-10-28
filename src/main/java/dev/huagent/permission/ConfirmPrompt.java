package dev.huagent.permission;

import dev.huagent.prompt.FramedPrompt;
import dev.huagent.prompt.TerminalText;
import dev.huagent.tool.ToolCall;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.PrintWriter;

/**
 * 权限确认在终端上的样式，PermissionGate 与 TeamConsole（队友的确认）共用；只影响显示，读到的回答不变。
 * full 是教程各入口与 hu --debug 的原样式：原因、工具名与调用 ID、完整参数、问题各占一行。
 * compact 是 hu 默认：以【权限确认】开头的一行 "【权限确认】工具：参数  问题[y/N] "，不显示原因与调用 ID；
 * 参数太长时问题另起一行，因为没换行的部分会成为行编辑器的提示符，提示符高过终端时 JLine 只画得出最后几行。
 * color 为 true 时（终端能解释 ANSI，见 Splash.ColorSupport）标记与问题显示为黄色，与对话内容区分。
 */
public final class ConfirmPrompt {
    /** 参数显示超过这个列数（中文占两列）时问题另起一行。 */
    static final int INLINE_COLUMNS = 200;
    private static final String LABEL = "【权限确认】";

    private final boolean compact;
    private final boolean color;

    private ConfirmPrompt(boolean compact, boolean color) {
        this.compact = compact;
        this.color = color;
    }

    public static ConfirmPrompt full() {
        return new ConfirmPrompt(false, false);
    }

    public static ConfirmPrompt compact(boolean color) {
        return new ConfirmPrompt(true, color);
    }

    /** hu 的选择：调试模式保持原来的多行样式，默认一行。 */
    public static ConfirmPrompt of(boolean debug, boolean color) {
        return debug ? full() : compact(color);
    }

    /** 打印确认提示，问题之后不换行，光标停在回答位置；调用方负责加锁与 flush。 */
    public void ask(PrintWriter output, String reason, ToolCall call, String question) {
        if (!compact) {
            output.println("权限确认> " + reason);
            output.println("工具> " + call.name() + " [" + call.id() + "]");
            output.println("参数> " + call.arguments());
            output.print(question + "[y/N] ");
            return;
        }
        String detail = detail(call.arguments());
        output.print(paint("1;33", LABEL) + paint("1", call.name()) + "：" + detail);
        if (FramedPrompt.displayWidth(detail) > INLINE_COLUMNS) {
            output.println();
        }
        output.print("  " + paint("33", question + "[y/N]") + " ");
    }

    /**
     * 参数只有一个字符串字段且没有需要转义的字符时直接显示它的值（如 shell 的命令）；
     * 否则显示压缩成一行的 JSON：JSON 转义换行与控制字符，TerminalText 再转义双向覆盖等格式字符，
     * 不会折成多行，也不会被终端当作控制序列执行。
     */
    static String detail(String arguments) {
        Object parsed;
        try {
            parsed = JSON.parse(arguments);
        } catch (RuntimeException e) {
            parsed = null;
        }
        if (parsed instanceof JSONObject object && object.size() == 1
                && object.values().iterator().next() instanceof String text
                && !text.isBlank() && TerminalText.visible(text).equals(text)) {
            return text;
        }
        return TerminalText.visible(JSON.toJSONString(parsed == null ? arguments : parsed));
    }

    private String paint(String sgr, String text) {
        return color ? "\u001b[" + sgr + "m" + text + "\u001b[0m" : text;
    }
}
