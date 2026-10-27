package dev.huagent.command;

import dev.huagent.skill.LoadSkillTool;
import dev.huagent.skill.Skill;
import dev.huagent.skill.SkillCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 每个技能一个提示词命令 /<技能名> [补充说明]：用户直接指定技能，不等模型自己决定是否 load_skill。
 * 正文每次重读，与 load_skill 相同的格式包进本轮输入，照常经过 UserPromptSubmit 并记入历史。
 * 技能文本不授予权限；名称不符合命令格式（以数字开头）的技能只能由模型加载。
 */
final class SkillCommands {
    static final String MARKER = "[技能命令]";

    private SkillCommands() {
    }

    static List<SlashCommand> create(SkillCatalog catalog, Consumer<String> warnings) {
        List<SlashCommand> commands = new ArrayList<>();
        for (Skill skill : catalog.skills()) {
            if (!CommandLine.isValidName(skill.name())) {
                warnings.accept("命令> 技能 " + skill.name() + " 的名称不以小写字母开头，不能用斜杠命令调用，仍可由模型 load_skill。");
                continue;
            }
            commands.add(SlashCommand.prompt(skill.name(), "/" + skill.name() + " [补充说明]",
                    "技能：" + ViewCommands.brief(skill.description()), (argument, context) -> CommandResult.submit(
                            prompt(skill, LoadSkillTool.render(catalog, skill), argument),
                            () -> "技能> 已按 " + skill.name() + " 的说明提交本轮任务。", "")));
        }
        return commands;
    }

    static String prompt(Skill skill, String rendered, String argument) {
        return MARKER + " 用户输入 /" + skill.name() + " 直接调用了下面的技能，请按技能说明完成本次任务，"
                + "不需要再用 load_skill 加载它。\n" + rendered
                + (argument.isEmpty() ? "" : "\n\n用户补充说明：" + argument);
    }
}
