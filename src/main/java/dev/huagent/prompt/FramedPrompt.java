package dev.huagent.prompt;

import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 只在每次输入上方画一条分隔线：空一行、分隔线、提示符 "❯ "，用户回车后空一行再输出回复。
 * 分隔线只标出用户输入，回复与工具输出前后都不画线，两者靠分隔线与 "❯ " 区分。
 * 分隔线里嵌入状态："── 模型 ── git:分支 ── 上下文剩余 99% · 998.2K / 1M ─────"，自动审批模式开启时最前面加 "自动审批 ── "。
 * 最大值来自 max-context-tokens（model.properties），用量由 ChatLoop 经 context 告知（接口 usage），
 * 用量未知时显示 "上下文未知 / 最大值"；分支由 HuMain 注入的 supplier 每次画线时读取。
 * 终端放不下时依次压缩：先把上下文缩到只剩余量，再从中间缩短模型名与分支名，
 * 最后去掉装饰，按自动审批、余量、模型、分支的顺序分配列数。
 * 只输出普通字符、不移动光标：Windows 上 Java 不会开启控制台的 ANSI 处理。
 * 分隔线每次按终端当前列数重新计算，比整行少一列，避免写满一行时部分终端再多换一行。
 */
public final class FramedPrompt implements PromptStyle {
    /** 列数探测不到时分隔线的长度。 */
    public static final int MIN_RULE = 10;
    /** 状态前后至少保留的分隔线字符数。 */
    private static final int LEAD = 2;
    private static final int TAIL = 2;
    /** 从中间缩短时模型名与分支名各自至少保留的列数。 */
    private static final int MIN_NAME = 5;

    private final String marker;
    private final char rule;
    private final IntSupplier columns;
    private volatile String model;
    private final int maxContextTokens;
    /** TeamConsole 在另一线程重画提示符，读到的是最近一次 context 的结果。 */
    private volatile int usedTokens = -1;
    /** 为 true 时分隔线最前面显示 "自动审批"，每次画线时现取（/auto 随时切换）。 */
    private final BooleanSupplier autoApprove;
    /** 当前 Git 分支，每次画线时现取；返回 null 或抛出异常时不显示。 */
    private final Supplier<String> gitBranch;

    public FramedPrompt(String marker, char rule, IntSupplier columns) {
        this(marker, rule, columns, null, 0, () -> false, () -> null);
    }

    /** model 为 null 或空白时分隔线不显示模型；maxContextTokens 不为正时不显示上下文。 */
    public FramedPrompt(String marker, char rule, IntSupplier columns, String model, int maxContextTokens) {
        this(marker, rule, columns, model, maxContextTokens, () -> false, () -> null);
    }

    public FramedPrompt(String marker, char rule, IntSupplier columns, String model, int maxContextTokens,
                        BooleanSupplier autoApprove) {
        this(marker, rule, columns, model, maxContextTokens, autoApprove, () -> null);
    }

    /** 新入口由 HuMain 注入 Git supplier；旧构造器不会自动探测仓库。 */
    public FramedPrompt(String marker, char rule, IntSupplier columns, String model, int maxContextTokens,
                        BooleanSupplier autoApprove, Supplier<String> gitBranch) {
        this.marker = Objects.requireNonNull(marker);
        this.rule = rule;
        this.columns = Objects.requireNonNull(columns);
        this.model = normalize(model);
        this.maxContextTokens = maxContextTokens;
        this.autoApprove = Objects.requireNonNull(autoApprove);
        this.gitBranch = Objects.requireNonNull(gitBranch);
    }

    private static String normalize(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    /**
     * 按终端字符集选择字符：能编码 ❯ 与 ─ 时用它们，否则（如 GBK 控制台）改用 "> " 与 "-"，
     * 同时避开 GBK 控制台把制表符按两列显示、分隔线折行的问题。
     */
    public static FramedPrompt forTerminal(Charset charset, IntSupplier columns, String model, int maxContextTokens) {
        return forTerminal(charset, columns, model, maxContextTokens, () -> false, () -> null);
    }

    public static FramedPrompt forTerminal(Charset charset, IntSupplier columns, String model, int maxContextTokens,
                                           BooleanSupplier autoApprove) {
        return forTerminal(charset, columns, model, maxContextTokens, autoApprove, () -> null);
    }

    public static FramedPrompt forTerminal(Charset charset, IntSupplier columns, String model, int maxContextTokens,
                                           BooleanSupplier autoApprove, Supplier<String> gitBranch) {
        return charset.newEncoder().canEncode("❯─")
                ? new FramedPrompt("❯ ", '─', columns, model, maxContextTokens, autoApprove, gitBranch)
                : new FramedPrompt("> ", '-', columns, model, maxContextTokens, autoApprove, gitBranch);
    }

    @Override
    public void context(int usedTokens) {
        this.usedTokens = usedTokens;
    }

    @Override
    public void model(String model) {
        this.model = normalize(model);
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

    /** 输入行的提示符（"❯ " 或 "> "）；LineEditor 固定输入框时据此认出主输入。 */
    String marker() {
        return marker;
    }

    String rule() {
        int cols = columns.getAsInt();
        int width = cols > 0 ? cols - 1 : MIN_RULE;
        // model 与 usedTokens 可能被其他线程改写，一次画线只读一次。
        String currentModel = model;
        int used = usedTokens;
        String name = currentModel == null ? null : TerminalText.visible(currentModel);
        String branch = branch();
        String auto = autoApprove.getAsBoolean() ? "自动审批" : null;
        String[] contexts = contexts(used);
        for (String context : contexts) {
            String status = join(auto, name, git(branch), context);
            if (fits(status, width)) {
                return framed(status, width);
            }
        }
        // 上下文已压到余量后，从中间缩短模型名与分支名：先缩较长的，等长后两者分摊，各保留至少 MIN_NAME 列。
        String remaining = contexts[contexts.length - 1];
        int nameWidth = name == null ? 0 : displayWidth(name);
        int branchWidth = branch == null ? 0 : displayWidth(branch);
        int others = displayWidth(join(auto, name, git(branch), remaining)) - nameWidth - branchWidth;
        int room = width - LEAD - TAIL - 2 - others;
        if (room >= Math.min(nameWidth, MIN_NAME) + Math.min(branchWidth, MIN_NAME)) {
            int nameRoom = Math.min(nameWidth, Math.max(room / 2, room - branchWidth));
            String status = join(auto, shorten(name, nameRoom), git(shorten(branch, room - nameRoom)), remaining);
            if (fits(status, width)) {
                return framed(status, width);
            }
        }
        // 极窄时去掉装饰，按自动审批、余量、模型、分支的顺序分配列数。
        List<String> parts = new ArrayList<>();
        int left = width;
        for (String field : new String[]{auto, remaining, name, git(branch)}) {
            if (field == null || left <= 0) {
                continue;
            }
            int budget = left - (parts.isEmpty() ? 0 : 1);
            if (budget <= 0) {
                break;
            }
            String text = shorten(field, budget);
            parts.add(text);
            left -= displayWidth(text) + (parts.size() == 1 ? 0 : 1);
        }
        String status = String.join(" ", parts);
        return status.isEmpty() ? String.valueOf(rule).repeat(width) : status;
    }

    /** 状态前后各留 LEAD、TAIL 个分隔线字符和一个空格后放得进 width 列。 */
    private static boolean fits(String status, int width) {
        return displayWidth(status) + LEAD + TAIL + 2 <= width;
    }

    private String framed(String status, int width) {
        if (status.isEmpty()) {
            return String.valueOf(rule).repeat(width);
        }
        String head = String.valueOf(rule).repeat(LEAD) + " " + status + " ";
        return head + String.valueOf(rule).repeat(width - displayWidth(head));
    }

    /** 分支读取失败时不显示，不影响输入。 */
    private String branch() {
        try {
            String branch = normalize(gitBranch.get());
            return branch == null ? null : TerminalText.visible(branch);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String git(String branch) {
        return branch == null ? null : "git:" + branch;
    }

    /** 用 " ── " 连接非空的部分。 */
    private String join(String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                present.add(part);
            }
        }
        return String.join(" " + String.valueOf(rule).repeat(LEAD) + " ", present);
    }

    /** 上下文状态从完整到最短的几种写法；没有配置最大值时只有 null。 */
    private String[] contexts(int used) {
        if (maxContextTokens <= 0) {
            return new String[]{null};
        }
        String max = abbreviate(maxContextTokens);
        if (used < 0) {
            return new String[]{"上下文未知 / " + max, "未知 / " + max, "未知"};
        }
        int remaining = Math.max(0, maxContextTokens - used);
        String percent = percent(remaining) + "%";
        String counts = abbreviate(remaining) + " / " + max;
        // ASCII 分隔线的终端（如 GBK 控制台）不一定能显示 ·。
        String dot = rule == '-' ? " . " : " · ";
        return new String[]{"上下文剩余 " + percent + dot + counts,
                "剩余 " + percent + dot + counts, percent + dot + counts, "剩余 " + percent, percent};
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

    /** 按显示列数从中间缩短到最多 max 列，保留两端："中文中间很长末尾" 缩到 11 列是 "中文...末尾"。 */
    static String shorten(String value, int max) {
        if (value == null || displayWidth(value) <= max) {
            return value;
        }
        if (max <= 0) {
            return "";
        }
        String dots = max >= 3 ? "..." : ".".repeat(max);
        int left = (max - dots.length() + 1) / 2;
        int right = max - dots.length() - left;
        int start = 0;
        int used = 0;
        while (start < value.length()) {
            int codePoint = value.codePointAt(start);
            int columns = wide(codePoint) ? 2 : 1;
            if (used + columns > left) {
                break;
            }
            used += columns;
            start += Character.charCount(codePoint);
        }
        int end = value.length();
        used = 0;
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            int columns = wide(codePoint) ? 2 : 1;
            if (used + columns > right) {
                break;
            }
            used += columns;
            end -= Character.charCount(codePoint);
        }
        return value.substring(0, start) + dots + value.substring(end);
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
