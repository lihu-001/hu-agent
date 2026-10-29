package dev.huagent.harness;

import dev.huagent.prompt.Margin;
import dev.huagent.prompt.TerminalText;
import dev.huagent.tool.ToolCall;

import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 非 debug 模式下助手的回复与工具调用在终端上的样子，只影响终端，消息历史与请求内容不变。调用方负责加锁与 flush。
 * plain 是原样式："助手> 回复"、"工具> 摘要"（ToolTrace.summary）各占一行、前后不空行，教程入口、测试与 hu --debug 使用。
 * marked 是 hu 交互终端的样式，与 Claude Code 相同，回复与每次工具调用各成一段，段与段之间空一行：
 * <pre>
 * ● 回复的第一行
 *   之后的行缩进两格
 *
 * ● read_file src/A.java
 *   ⎿  177 行
 * </pre>
 * color 为 true 时（终端能解释 ANSI）工具的 ● 为绿色、失败时为红色，工具名加粗，结果变暗、失败时为红色。
 * 需要确认的工具在执行前先空行（beforeTool），确认行与随后的摘要同属一段。
 * marked 的左边距与折行由行编辑器套用 margin（见 Margin）：其他顶格的输出补两列，长行折行后与正文对齐。
 * headless 是 hu -p 的样式：与 plain 相同，但不显示没有工具调用的回答（最终回答），hu -p 把它单独写到标准输出。
 */
public final class Transcript {
    private static final String PLAIN_REPLY = "助手> ";
    /** 非空行的行首；换行符保持原样。 */
    private static final Pattern LINE_START = Pattern.compile("^(?=.)", Pattern.MULTILINE);

    private final boolean marked;
    private final String bullet;
    private final String branch;
    private final boolean color;
    private final boolean headless;
    /** 本轮已经显示过一段：下一段之前要空一行。 */
    private boolean shown;
    /** 已经为下一段空过行（beforeTool），摘要不再空行。 */
    private boolean separated;

    private Transcript(boolean marked, String bullet, String branch, boolean color, boolean headless) {
        this.marked = marked;
        this.bullet = bullet;
        this.branch = branch;
        this.color = color;
        this.headless = headless;
    }

    public static Transcript plain() {
        return new Transcript(false, null, null, false, false);
    }

    public static Transcript marked(String bullet, String branch, boolean color) {
        return new Transcript(true, bullet, branch, color, false);
    }

    public static Transcript headless() {
        return new Transcript(false, null, null, false, true);
    }

    /** 按终端字符集选择符号：能编码 ● 与 ⎿ 时用它们，否则（如 GBK 控制台，● 按两列显示会错位）改用 * 与 -。 */
    public static Transcript forTerminal(Charset charset, boolean color) {
        return charset.newEncoder().canEncode("●⎿") ? marked("●", "⎿", color) : marked("*", "-", color);
    }

    /** 一轮开始（用户回车之后）：第一段之前不空行，提示符样式已经在输入下面空了一行。 */
    public void startTurn() {
        shown = false;
        separated = false;
    }

    /** 助手的回复（含与工具调用一起返回的中间说明）。 */
    public void reply(PrintWriter output, String text) {
        if (!marked) {
            output.println(PLAIN_REPLY + text);
            return;
        }
        separate(output);
        String[] lines = text.strip().split("\\R", -1);
        output.println(bullet + " " + lines[0]);
        for (int i = 1; i < lines.length; i++) {
            output.println(lines[i].isEmpty() ? "" : "  " + lines[i]);
        }
        shown = true;
        separated = false;
    }

    /**
     * 模型一次回答里的文字；last 为 true 表示这次回答没有工具调用，是本轮的最终回答（Goal Gate 要求续跑时除外）。
     * headless 不显示最终回答，其余样式与 reply(output, text) 相同。
     */
    public void reply(PrintWriter output, String text, boolean last) {
        if (!(headless && last)) {
            reply(output, text);
        }
    }

    /**
     * 随本轮输入附加的图片（见 InputImages）：attached 是已附加的，skipped 是没能附加的及原因。
     * marked 时与工具结果行一样以 ⎿ 开头，单独成一段；没能附加的为红色。
     */
    public void images(PrintWriter output, List<String> attached, List<String> skipped) {
        if (!marked) {
            attached.forEach(item -> output.println("图片> 已附加 " + TerminalText.visible(item)));
            skipped.forEach(item -> output.println("图片> 未附加 " + TerminalText.visible(item)));
            return;
        }
        separate(output);
        for (String item : attached) {
            output.println("  " + paint("2", branch + "  已附加图片 " + TerminalText.visible(item)));
        }
        for (String item : skipped) {
            output.println("  " + paint("31", branch + "  未附加图片 " + TerminalText.visible(item)));
        }
        shown = true;
        separated = false;
    }

    /**
     * 本地命令（如 /tools、/status）的输出：marked 时每行缩进两格，与回复的续行对齐，
     * 列表项原有的缩进在此之上保留，层次不变；plain 原样输出。
     */
    public void notice(PrintWriter output, String text) {
        output.println(marked ? LINE_START.matcher(text).replaceAll("  ") : text);
    }

    /** marked 的左边距与折行，符号与段首、结果行相同；plain 为 null，输出原样。 */
    public Margin margin() {
        return marked ? new Margin(bullet, branch) : null;
    }

    /** 执行工具之前调用：先空行，执行中弹出的权限确认就排在这一段里。 */
    public void beforeTool(PrintWriter output) {
        if (marked) {
            separate(output);
        }
    }

    /** 一次工具调用的摘要：调用了什么、结果如何；完整结果只给模型。 */
    public void tool(PrintWriter output, ToolCall call, String result) {
        if (!marked) {
            output.println(ToolTrace.summary(call, result));
            return;
        }
        separate(output);
        boolean failed = ToolTrace.failed(result);
        String subject = ToolTrace.subject(call);
        output.println(paint(failed ? "31" : "32", bullet) + " " + paint("1", TerminalText.visible(call.name()))
                + (subject.isEmpty() ? "" : " " + subject));
        output.println("  " + paint(failed ? "31" : "2", branch + "  " + ToolTrace.outcome(call.name(), result)));
        shown = true;
        separated = false;
    }

    private void separate(PrintWriter output) {
        if (shown && !separated) {
            output.println();
        }
        separated = true;
    }

    private String paint(String sgr, String text) {
        return color ? "\u001b[" + sgr + "m" + text + "\u001b[0m" : text;
    }
}
