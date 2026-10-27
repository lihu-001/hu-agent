package dev.huagent.goal;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 终端输入的 /goal 会话命令：不是提示词，由主循环在 UserPromptSubmit 之前识别。
 * “/goal” 查看状态，“/goal clear”（及别名）清除，“/goal 条件” 设置或替换目标。
 */
public record GoalCommand(Kind kind, String condition) {
    public static final String PREFIX = "/goal";
    public static final int MAX_CONDITION_CHARS = 4000;
    public static final Set<String> CLEAR_ALIASES = Set.of("clear", "stop", "off", "reset", "none", "cancel");

    public enum Kind {
        STATUS, SET, CLEAR
    }

    public GoalCommand {
        Objects.requireNonNull(kind);
        if (kind == Kind.SET && (condition == null || condition.isBlank())) {
            throw new IllegalArgumentException("完成条件不能为空");
        }
    }

    /** 不是 /goal 命令时返回 null（例如 “/goals” 或普通文字）；条件过长时抛出 IllegalArgumentException。 */
    public static GoalCommand parse(String line) {
        String text = line == null ? "" : line.strip();
        if (!text.startsWith(PREFIX)) {
            return null;
        }
        String rest = text.substring(PREFIX.length());
        if (rest.isEmpty()) {
            return new GoalCommand(Kind.STATUS, null);
        }
        if (!Character.isWhitespace(rest.charAt(0))) {
            return null;
        }
        String argument = rest.strip();
        if (CLEAR_ALIASES.contains(argument.toLowerCase(Locale.ROOT))) {
            return new GoalCommand(Kind.CLEAR, null);
        }
        if (argument.length() > MAX_CONDITION_CHARS) {
            throw new IllegalArgumentException("完成条件最多 " + MAX_CONDITION_CHARS + " 个字符，当前 "
                    + argument.length() + " 个，目标未设置。");
        }
        return new GoalCommand(Kind.SET, argument);
    }
}
