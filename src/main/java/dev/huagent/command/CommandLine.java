package dev.huagent.command;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终端输入中的斜杠命令：“/名称” 或 “/名称 参数”，名称为小写字母开头的小写字母、数字与连字符。
 * 其他以 / 开头的文字（如 “/usr/bin/env”、“/Goal”、“/”）不是命令形式，仍作为普通提示词。
 */
public record CommandLine(String name, String argument) {
    /** 名称后必须是空白（含全角空格）或结尾；参数可以包含任何字符。 */
    private static final Pattern FORM = Pattern.compile("/([a-z][a-z0-9-]*)(?:\\s+(.*))?",
            Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);

    /** 不是命令形式时返回 null；参数去掉首尾空白，没有参数时为空串。 */
    public static CommandLine parse(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = FORM.matcher(line.strip());
        if (!matcher.matches()) {
            return null;
        }
        String argument = matcher.group(2) == null ? "" : matcher.group(2).strip();
        return new CommandLine(matcher.group(1), argument);
    }

    /** 名称本身是否合法（命令注册时校验）。 */
    public static boolean isValidName(String name) {
        CommandLine parsed = parse("/" + name);
        return parsed != null && parsed.name().equals(name) && parsed.argument().isEmpty();
    }
}
