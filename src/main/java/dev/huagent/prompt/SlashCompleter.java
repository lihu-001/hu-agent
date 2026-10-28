package dev.huagent.prompt;

import dev.huagent.command.SlashCommand;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 斜杠命令补全：光标前的全部输入是 "/命令名前缀" 时，给出名称或别名以此开头的命令。
 * 参数、普通提示词和不是命令形式的输入（如 /usr/bin/env）都没有候选，行编辑器也就不显示列表。
 * 命令（含技能命令）在会话装配之后才确定，所以每次补全时现取。
 *
 * <p>说明直接拼进候选的显示文字：JLine 把单独的说明放在括号里右对齐，这里改成名称对齐成一列、说明紧跟其后，
 * 过长时按终端宽度截断，每个候选只占一行。
 */
final class SlashCompleter implements Completer {
    /** 与 CommandLine 的命令名规则一致，允许只输入了 "/"。 */
    private static final Pattern PREFIX = Pattern.compile("/[a-z0-9-]*");
    private static final String GAP = "  ";
    /** 列表的缩进与 JLine 要求的行尾余量。 */
    private static final int MARGIN = 4;

    private final Supplier<? extends Collection<SlashCommand>> commands;

    SlashCompleter(Supplier<? extends Collection<SlashCommand>> commands) {
        this.commands = Objects.requireNonNull(commands);
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        candidates.addAll(candidates(line.line().substring(0, line.cursor()), reader.getTerminal().getWidth()));
    }

    /** 光标前的输入 typed 是否有候选。 */
    boolean matches(String typed) {
        return !candidates(typed, 0).isEmpty();
    }

    /** typed 是光标前的全部输入，不是命令前缀时返回空列表；columns 是终端列数，不为正时说明不截断。 */
    List<Candidate> candidates(String typed, int columns) {
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
