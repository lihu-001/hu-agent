package dev.huagent.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 一个斜杠命令。本地命令返回 PRINT 或 EXIT：不请求模型、不触发 Hook、不进入对话历史。
 * 提示词命令（prompt 为 true）可能返回 SUBMIT，把展开后的文字作为本轮任务交给模型，照常经过 UserPromptSubmit。
 * takesArgument 为 false 时，带参数的输入由注册表直接拒绝，不调用 handler。
 * choices 不为 null 时给出参数的候选（如 /model 的模型名称），行编辑器在 "/命令 " 之后列出供选择。
 */
public record SlashCommand(String name, List<String> aliases, String usage, String description,
                           boolean takesArgument, boolean prompt, Handler handler, Supplier<List<Choice>> choices) {

    @FunctionalInterface
    public interface Handler {
        /** argument 已去掉首尾空白，没有参数时为空串。 */
        CommandResult run(String argument, CommandContext context) throws IOException, InterruptedException;
    }

    /** 参数候选：value 是填进输入行的参数，note 是列表里跟在后面的简短说明，可以为空串。 */
    public record Choice(String value, String note) {
        public Choice {
            Objects.requireNonNull(value);
            Objects.requireNonNull(note);
        }
    }

    public SlashCommand {
        aliases = List.copyOf(aliases);
        for (String each : concat(name, aliases)) {
            if (each == null || !CommandLine.isValidName(each)) {
                throw new IllegalArgumentException("命令名必须是小写字母开头的小写字母、数字与连字符：" + each);
            }
        }
        Objects.requireNonNull(usage);
        Objects.requireNonNull(description);
        Objects.requireNonNull(handler);
    }

    /** 不接受参数的本地命令，用法就是 “/名称”。 */
    public static SlashCommand local(String name, String description, Handler handler) {
        return new SlashCommand(name, List.of(), "/" + name, description, false, false, handler, null);
    }

    /** 参数可选的本地命令，usage 写明参数含义。 */
    public static SlashCommand localWithArgument(String name, String usage, String description, Handler handler) {
        return new SlashCommand(name, List.of(), usage, description, true, false, handler, null);
    }

    /** 提示词命令，参数可选。 */
    public static SlashCommand prompt(String name, String usage, String description, Handler handler) {
        return new SlashCommand(name, List.of(), usage, description, true, true, handler, null);
    }

    public SlashCommand withAliases(String... names) {
        return new SlashCommand(name, List.of(names), usage, description, takesArgument, prompt, handler, choices);
    }

    /** 每次显示列表时现取候选，不能阻塞（不发网络请求）。 */
    public SlashCommand withChoices(Supplier<List<Choice>> source) {
        return new SlashCommand(name, aliases, usage, description, takesArgument, prompt, handler,
                Objects.requireNonNull(source));
    }

    /** 名称在前、别名在后。 */
    public List<String> names() {
        return concat(name, aliases);
    }

    private static List<String> concat(String name, List<String> aliases) {
        List<String> all = new ArrayList<>();
        all.add(name);
        all.addAll(aliases);
        return all;
    }
}
