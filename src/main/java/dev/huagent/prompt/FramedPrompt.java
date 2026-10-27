package dev.huagent.prompt;

import dev.huagent.harness.TurnEvents;

import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * 把每次输入框在两条分隔线之间：空一行、上分隔线、提示符 "❯ "，用户回车后再画下分隔线。
 * 只输出普通字符、不移动光标：Windows 上 Java 不会开启控制台的 ANSI 处理，所以下分隔线在回车后才出现。
 * 分隔线每次按终端当前列数重新计算，比整行少一列，避免写满一行时部分终端再多换一行。
 */
public final class FramedPrompt implements PromptStyle {
    /** 列数探测不到或终端过窄时分隔线的最短长度。 */
    public static final int MIN_RULE = 10;

    private final String marker;
    private final char rule;
    private final IntSupplier columns;

    public FramedPrompt(String marker, char rule, IntSupplier columns) {
        this.marker = Objects.requireNonNull(marker);
        this.rule = rule;
        this.columns = Objects.requireNonNull(columns);
    }

    /**
     * 按终端字符集选择字符：能编码 ❯ 与 ─ 时用它们，否则（如 GBK 控制台）改用 "> " 与 "-"，
     * 同时避开 GBK 控制台把制表符按两列显示、分隔线折行的问题。
     */
    public static FramedPrompt forTerminal(Charset charset, IntSupplier columns) {
        return charset.newEncoder().canEncode("❯─")
                ? new FramedPrompt("❯ ", '─', columns) : new FramedPrompt("> ", '-', columns);
    }

    @Override
    public void begin(PrintWriter output) {
        output.println();
        output.println(rule());
        output.print(marker);
    }

    @Override
    public void end(PrintWriter output, String line) {
        if (line == null || TurnEvents.isWake(line)) {
            // 用户没有回车，先结束提示符这一行；不画下分隔线，下次等待输入时重新画框。
            output.println();
        } else {
            output.println(rule());
        }
    }

    String rule() {
        return String.valueOf(rule).repeat(Math.max(MIN_RULE, columns.getAsInt() - 1));
    }
}
