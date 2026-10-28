package dev.huagent.prompt;

import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * 只在每次输入上方画一条分隔线：空一行、分隔线、提示符 "❯ "，用户回车后空一行再输出回复。
 * 分隔线只标出用户输入，回复与工具输出前后都不画线，两者靠分隔线与 "❯ " 区分。
 * 分隔线里嵌入状态："── 模型 ── 上下文剩余 998.2K（99%）/ 最大 1M token ─────"。最大值来自 max-context-tokens（model.properties），
 * 用量由 ChatLoop 经 context 告知（接口 usage）；用量未知时只显示最大值，终端太窄放不下时退回不带状态的分隔线。
 * 只输出普通字符、不移动光标：Windows 上 Java 不会开启控制台的 ANSI 处理。
 * 分隔线每次按终端当前列数重新计算，比整行少一列，避免写满一行时部分终端再多换一行。
 */
public final class FramedPrompt implements PromptStyle {
    /** 列数探测不到或终端过窄时分隔线的最短长度。 */
    public static final int MIN_RULE = 10;
    /** 状态前后至少保留的分隔线字符数。 */
    private static final int LEAD = 2;
    private static final int TAIL = 2;

    private final String marker;
    private final char rule;
    private final IntSupplier columns;
    private volatile String model;
    private final int maxContextTokens;
    /** TeamConsole 在另一线程重画提示符，读到的是最近一次 context 的结果。 */
    private volatile int usedTokens = -1;

    public FramedPrompt(String marker, char rule, IntSupplier columns) {
        this(marker, rule, columns, null, 0);
    }

    /** model 为 null 或空白时分隔线不显示模型；maxContextTokens 不为正时不显示上下文。 */
    public FramedPrompt(String marker, char rule, IntSupplier columns, String model, int maxContextTokens) {
        this.marker = Objects.requireNonNull(marker);
        this.rule = rule;
        this.columns = Objects.requireNonNull(columns);
        this.model = normalizeModel(model);
        this.maxContextTokens = maxContextTokens;
    }

    private static String normalizeModel(String model) {
        return model == null || model.isBlank() ? null : model.strip();
    }

    /**
     * 按终端字符集选择字符：能编码 ❯ 与 ─ 时用它们，否则（如 GBK 控制台）改用 "> " 与 "-"，
     * 同时避开 GBK 控制台把制表符按两列显示、分隔线折行的问题。
     */
    public static FramedPrompt forTerminal(Charset charset, IntSupplier columns, String model, int maxContextTokens) {
        return charset.newEncoder().canEncode("❯─")
                ? new FramedPrompt("❯ ", '─', columns, model, maxContextTokens)
                : new FramedPrompt("> ", '-', columns, model, maxContextTokens);
    }

    @Override
    public void context(int usedTokens) {
        this.usedTokens = usedTokens;
    }

    @Override
    public void model(String model) {
        this.model = normalizeModel(model);
    }

    @Override
    public void begin(PrintWriter output) {
        output.println();
        output.println(rule());
        output.print(marker);
    }

    @Override
    public void end(PrintWriter output, String line) {
        // 用户回车后终端已换行，这里再空一行把输入与回复隔开；
        // 没有回车（输入结束或程序事件唤醒）时，这一换行结束提示符这一行。
        output.println();
    }

    String rule() {
        int width = Math.max(MIN_RULE, columns.getAsInt() - 1);
        String bar = String.valueOf(rule).repeat(LEAD);
        String context = context();
        String currentModel = model;
        String status = currentModel == null ? context
                : context == null ? currentModel : currentModel + " " + bar + " " + context;
        if (status != null) {
            String head = bar + " " + status + " ";
            int used = displayWidth(head);
            if (used + TAIL <= width) {
                return head + String.valueOf(rule).repeat(width - used);
            }
        }
        return String.valueOf(rule).repeat(width);
    }

    private String context() {
        if (maxContextTokens <= 0) {
            return null;
        }
        int used = usedTokens;
        String max = "最大 " + abbreviate(maxContextTokens) + " token";
        if (used < 0) {
            return "上下文" + max;
        }
        int remaining = Math.max(0, maxContextTokens - used);
        return "上下文剩余 " + abbreviate(remaining) + "（" + percent(remaining) + "%）/ " + max;
    }

    /** 剩余占最大值的百分比，向下取整：只有完全没用时才显示 100%，余量不足 1% 时显示 0%。 */
    private long percent(int remaining) {
        return remaining * 100L / maxContextTokens;
    }

    /** 38234 → 38.2K，50000 → 50K，1200000 → 1.2M。 */
    static String abbreviate(int chars) {
        if (chars < 1000) {
            return String.valueOf(chars);
        }
        boolean thousands = chars < 999_950;
        String text = String.format(Locale.ROOT, "%.1f", chars / (thousands ? 1e3 : 1e6));
        return (text.endsWith(".0") ? text.substring(0, text.length() - 2) : text) + (thousands ? "K" : "M");
    }

    /** 终端显示列数：中日韩文字、全角符号与常见 emoji 占两列，其余一列。 */
    public static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            width += wide(codePoint) ? 2 : 1;
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private static boolean wide(int c) {
        return c >= 0x1100 && (c <= 0x115F
                || c >= 0x2E80 && c <= 0xA4CF && c != 0x303F
                || c >= 0xAC00 && c <= 0xD7A3
                || c >= 0xF900 && c <= 0xFAFF
                || c >= 0xFE30 && c <= 0xFE4F
                || c >= 0xFF00 && c <= 0xFF60
                || c >= 0xFFE0 && c <= 0xFFE6
                || c >= 0x1F300 && c <= 0x1F64F
                || c >= 0x1F900 && c <= 0x1F9FF
                || c >= 0x20000 && c <= 0x3FFFD);
    }
}
