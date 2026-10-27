package dev.huagent.command;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 组装点回调：主 Agent（团队模式为 Lead）的各个部件就绪后创建斜杠命令注册表。
 * null 表示本课机制关闭（上一课入口），组装行为与 S17 完全相同。
 */
@FunctionalInterface
public interface CommandStarter {

    /** 返回的注册表交给主 Agent 的 ChatLoop；warnings 接收重名等启动告警。 */
    CommandRegistry bind(CommandSources sources, Consumer<String> warnings);

    /** 本课的全部内置命令，之后每个技能一个 /<技能名> 命令（与内置命令重名的跳过）。 */
    static CommandStarter builtin() {
        return (sources, warnings) -> {
            List<SlashCommand> commands = new ArrayList<>(SessionCommands.create(sources));
            commands.addAll(ViewCommands.create(sources));
            commands.addAll(SkillCommands.create(sources.catalog(), warnings));
            return new CommandRegistry(commands, warnings);
        };
    }
}
