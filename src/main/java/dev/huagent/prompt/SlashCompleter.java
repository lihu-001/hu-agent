package dev.huagent.prompt;

import dev.huagent.command.SlashCommand;
import org.jline.reader.Candidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 斜杠命令补全的候选（SlashMenu 显示）：输入是 "/命令名前缀" 时，给出名称或别名以此开头的命令；
 * 输入是 "/命令名 参数前缀" 且命令提供参数候选（如 /model 的模型名称）时，给出包含已输入部分的参数。
 * 其他参数、普通提示词和不是命令形式的输入（如 /usr/bin/env）都没有候选，行编辑器也就不显示列表。
 * 命令（含技能命令）在会话装配之后才确定，所以每次现取。
 *
 * <p>候选的显示文字是名称对齐成一列、说明紧跟其后，过长时按终端宽度截断，每个候选只占一行。
 */
final class SlashCompleter {
    /** 与 CommandLine 的命令名规则一致，允许只输入了 "/"。 */
    private static final Pattern PREFIX = Pattern.compile("/[a-z0-9-]*");
    /** 命令名后至少一个空格，参数还没有输入完（不含空白）。 */
    private static final Pattern ARGUMENT = Pattern.compile("/([a-z0-9-]+) +(\\S*)");
    private static final String GAP = "  ";
    /** 列表的缩进与行尾余量，写满一行时部分终端会多换一行。 */
    static final int MARGIN = 4;

    private final Supplier<? extends Collection<SlashCommand>> commands;

    SlashCompleter(Supplier<? extends Collection<SlashCommand>> commands) {
        this.commands = Objects.requireNonNull(commands);
    }

    /** typed 是光标前的全部输入，不是命令前缀时返回空列表；columns 是终端列数，不为正时说明不截断。 */
    List<Candidate> candidates(String typed, int columns) {
        Matcher argument = ARGUMENT.matcher(typed);
        if (argument.matches()) {
            return choices(argument.group(1), argument.group(2), columns);
        }
        if (!PREFIX.matcher(typed).matches()) {
            return List.of();
        }
        String prefix = typed.substring(1);
        List<String[]> found = new ArrayList<>();
        int nameWidth = 0;
        for (SlashCommand command : commands.get()) {
            for (String name : command.names()) {
                if (name.startsWith(prefix)) {
                    String description = name.equals(command.name()) ? command.description()
                            : command.description() + "（/" + command.name() + " 的别名）";
                    found.add(new String[] {"/" + name, description});
                    nameWidth = Math.max(nameWidth, name.length() + 1);
                }
            }
        }
        int room = columns - MARGIN - nameWidth - GAP.length();
        List<Candidate> result = new ArrayList<>();
        for (String[] each : found) {
            String display = each[0] + " ".repeat(nameWidth - each[0].length()) + GAP
                    + (columns > 0 ? truncate(each[1], room) : each[1]);
            // complete 为 true：选中后补一个空格，接着输入参数或直接回车都行。
            result.add(new Candidate(each[0], display, null, null, null, null, true));
        }
        return result;
    }

    /**
     * 参数候选：值包含已输入部分（不区分大小写）的项，保持命令给出的顺序。值对齐成一列、说明跟在后面；
     * 选中后整行替换为 "/命令名 值"，不补空格，再回车执行。
     */
    private List<Candidate> choices(String name, String typed, int columns) {
        SlashCommand command = commands.get().stream().filter(each -> each.names().contains(name))
                .findFirst().orElse(null);
        if (command == null || command.choices() == null) {
            return List.of();
        }
        String needle = typed.toLowerCase(Locale.ROOT);
        List<SlashCommand.Choice> found = command.choices().get().stream()
                .filter(choice -> choice.value().toLowerCase(Locale.ROOT).contains(needle)).toList();
        int width = found.stream().mapToInt(choice -> FramedPrompt.displayWidth(choice.value())).max().orElse(0);
        List<Candidate> result = new ArrayList<>();
        for (SlashCommand.Choice choice : found) {
            String display = choice.note().isEmpty() ? choice.value() : choice.value()
                    + " ".repeat(width - FramedPrompt.displayWidth(choice.value())) + GAP + choice.note();
            result.add(new Candidate("/" + name + " " + choice.value(),
                    columns > 0 ? truncate(display, columns - MARGIN) : display, null, null, null, null, false));
        }
        return result;
    }

    /** 按显示列数截断，超出时以 … 结尾；room 不足一个字符时返回空串。 */
    static String truncate(String text, int room) {
        if (FramedPrompt.displayWidth(text) <= room) {
            return text;
        }
        if (room < 1) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int width = FramedPrompt.displayWidth(Character.toString(codePoint));
            if (used + width > room - 1) {
                break;
            }
            kept.appendCodePoint(codePoint);
            used += width;
            i += Character.charCount(codePoint);
        }
        return kept + "…";
    }
}
