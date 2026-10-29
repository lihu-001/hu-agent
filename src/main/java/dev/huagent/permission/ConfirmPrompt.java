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
 * hu 的手动确认多一个选项 a（本次会话不再询问，范围见 SessionScope），问题之后为 "[y/N，a=本次会话不再询问这条命令]"。
 */
public final class ConfirmPrompt {
    /** 参数显示超过这个列数（中文占两列）时问题另起一行。 */
    static final int INLINE_COLUMNS = 200;
    private static final String LABEL = "【权限确认】";
    private static final String APPROVED_LABEL = "【自动批准】";
    private static final String SESSION_LABEL = "【本会话允许】";

    /** 确认的回答：拒绝、只允许本次、本次会话不再询问同一范围（见 SessionScope）。 */
    public enum Answer {
        DENY, ONCE, SESSION
    }

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
        ask(output, reason, call, null, question);
    }

    /**
     * preview 非 null 时（写文件、编辑与多行命令，见 ChangePreview）与 Claude Code 一样显示改动本身：
     * compact 为一行 "【权限确认】工具：摘要"，其下逐行显示带行号的 diff 或命令，问题单独一行；
     * full 在参数之后追加同样的预览。
     */
    public void ask(PrintWriter output, String reason, ToolCall call, ChangePreview preview, String question) {
        ask(output, reason, call, preview, question, null);
    }

    /**
     * session 非 null 时（hu 的手动确认）多一个选项 a，问题之后为 "[y/N，a=本次会话不再询问" + session + "]"，
     * session 是 SessionScope.label；为 null 时只有 [y/N]，与之前相同。
     */
    public void ask(PrintWriter output, String reason, ToolCall call, ChangePreview preview, String question,
                    String session) {
        String choices = question + (session == null ? "[y/N]" : "[y/N，a=本次会话不再询问" + session + "]");
        if (!compact) {
            output.println("权限确认> " + reason);
            output.println("工具> " + call.name() + " [" + call.id() + "]");
            output.println("参数> " + call.arguments());
            printPreview(output, preview, "预览> ");
            output.print(choices + " ");
            return;
        }
        if (preview != null) {
            output.println(paint("1;33", LABEL) + paint("1", call.name()) + "：" + TerminalText.visible(preview.summary()));
            printPreview(output, preview, null);
            output.print("  " + paint("33", choices) + " ");
            return;
        }
        String detail = detail(call.arguments());
        output.print(paint("1;33", LABEL) + paint("1", call.name()) + "：" + detail);
        if (FramedPrompt.displayWidth(detail) > INLINE_COLUMNS) {
            output.println();
        }
        output.print("  " + paint("33", choices) + " ");
    }

    /** y / yes 只允许本次；提供了会话选项时 a / always 放行本次会话；其余回答（含空行与输入结束）都是拒绝。 */
    public static Answer answer(String line, boolean session) {
        String choice = line == null ? "" : line.strip();
        if (choice.equalsIgnoreCase("y") || choice.equalsIgnoreCase("yes")) {
            return Answer.ONCE;
        }
        return session && (choice.equalsIgnoreCase("a") || choice.equalsIgnoreCase("always"))
                ? Answer.SESSION : Answer.DENY;
    }

    /**
     * 自动审批时的记录，不提问、以换行结束；member 是队友名，Lead 与子代理为 null。
     * full 与 ask 相同列出原因、工具与调用 ID、参数，最后一行说明已自动批准；
     * compact 为一行 "【自动批准】工具：参数"，队友的调用在工具名前加 "[名字] "。调用方负责加锁与 flush。
     */
    public void approved(PrintWriter output, String reason, ToolCall call, String member) {
        approved(output, reason, call, null, member);
    }

    /** preview 非 null 时与 ask 相同，在记录之下显示改动本身，自动批准的改动也看得到改了什么。 */
    public void approved(PrintWriter output, String reason, ToolCall call, ChangePreview preview, String member) {
        record(output, APPROVED_LABEL, "自动审批模式，已自动批准。", reason, call, preview, member);
    }

    /**
     * 本次会话已放行（之前确认时回答过 a）的调用的记录，与 approved 只差标记：
     * compact 为一行 "【本会话允许】工具：参数"，full 最后一行说明本次会话已允许。
     */
    public void allowed(PrintWriter output, String reason, ToolCall call, ChangePreview preview, String member) {
        record(output, SESSION_LABEL, "本次会话已允许，不再询问。", reason, call, preview, member);
    }

    private void record(PrintWriter output, String label, String note, String reason, ToolCall call,
                        ChangePreview preview, String member) {
        String who = member == null ? "" : "[" + member + "] ";
        if (!compact) {
            output.println("权限确认> " + who + reason);
            output.println("工具> " + call.name() + " [" + call.id() + "]");
            output.println("参数> " + call.arguments());
            printPreview(output, preview, "预览> ");
            output.println(note);
            return;
        }
        output.println(paint("1;33", label) + who + paint("1", call.name()) + "："
                + (preview == null ? detail(call.arguments()) : TerminalText.visible(preview.summary())));
        printPreview(output, preview, null);
    }

    /**
     * 预览明细：行号右对齐，新增行以 + 标出（绿色），删除行以 - 标出（红色），说明行变暗；
     * 制表符展开成四个空格，其余控制字符转义，不会被终端执行。label 非 null 时先打印一行摘要（full 样式）。
     */
    private void printPreview(PrintWriter output, ChangePreview preview, String label) {
        if (preview == null) {
            return;
        }
        if (label != null) {
            output.println(label + TerminalText.visible(preview.summary()));
        }
        for (ChangePreview.Line line : preview.lines()) {
            String text = TerminalText.visible(line.text().replace("\t", "    "));
            String number = line.number() == 0 ? "     " : String.format("%5d", line.number());
            output.println(switch (line.kind()) {
                case ADDED -> paint("32", "  " + number + " + " + text);
                case REMOVED -> paint("31", "  " + number + " - " + text);
                case CONTEXT -> "  " + number + "   " + text;
                case NOTE -> paint("2", "          " + text);
                case PLAIN -> "    " + text;
            });
        }
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
