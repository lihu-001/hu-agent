package dev.huagent.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 斜杠命令注册表：按名称与别名查找，/help 由注册表自己提供并排在最前。
 * dispatch 对不是命令形式的输入返回 null，主循环把它当作普通提示词；
 * 未知命令和多余参数只返回提示，不交给模型，避免拼错的命令被当作提示词发出去。
 */
public final class CommandRegistry {
    private final Map<String, SlashCommand> lookup = new HashMap<>();
    private final List<SlashCommand> commands = new ArrayList<>();

    /** 重名（含别名）的命令跳过并告警，先注册的优先：内置命令在前，技能命令在后。 */
    public CommandRegistry(List<SlashCommand> commands, Consumer<String> warnings) {
        register(SlashCommand.local("help", "列出全部斜杠命令", (argument, context) -> CommandResult.print(help())),
                warnings);
        commands.forEach(command -> register(command, warnings));
    }

    private void register(SlashCommand command, Consumer<String> warnings) {
        for (String name : command.names()) {
            if (lookup.containsKey(name)) {
                warnings.accept("命令> /" + name + " 与已有命令重名，/" + command.name() + " 未注册。");
                return;
            }
        }
        command.names().forEach(name -> lookup.put(name, command));
        commands.add(command);
    }

    public List<SlashCommand> commands() {
        return List.copyOf(commands);
    }

    /** 按名称或别名查找，没有时返回 null。 */
    public SlashCommand find(String name) {
        return lookup.get(name);
    }

    public CommandResult dispatch(String line, CommandContext context) throws IOException, InterruptedException {
        CommandLine parsed = CommandLine.parse(line);
        if (parsed == null) {
            return null;
        }
        SlashCommand command = lookup.get(parsed.name());
        if (command == null) {
            return CommandResult.print(unknown(parsed.name()));
        }
        if (!command.takesArgument() && !parsed.argument().isEmpty()) {
            return CommandResult.print("命令> /" + parsed.name() + " 不接受参数，未执行。用法：" + command.usage());
        }
        return command.handler().run(parsed.argument(), context);
    }

    public String help() {
        StringBuilder text = new StringBuilder("命令> 本地命令只在终端执行：不请求模型、不触发 Hook、不进入对话。"
                + "标 * 的命令会把内容作为本轮任务交给模型，照常经过 UserPromptSubmit。");
        for (SlashCommand command : commands) {
            text.append("\n  ").append(command.prompt() ? "* " : "").append(command.usage());
            if (!command.aliases().isEmpty()) {
                text.append("（").append(String.join("、", command.aliases().stream().map(n -> "/" + n).toList()))
                        .append("）");
            }
            text.append(" — ").append(command.description());
        }
        return text.append("\n命令> 不带斜杠的 exit / quit 也能退出；以 / 开头但不是命令格式的输入（如 /usr/bin/env）按普通提示词发送。")
                .toString();
    }

    private String unknown(String name) {
        List<String> similar = lookup.keySet().stream()
                .filter(known -> known.startsWith(name) || name.startsWith(known))
                .sorted()
                .map(known -> "/" + known)
                .toList();
        return "命令> 未知命令 /" + name + "，未发送给模型。"
                + (similar.isEmpty() ? "" : "你是不是想输入 " + String.join("、", similar) + "？")
                + "输入 /help 查看全部命令。";
    }
}
