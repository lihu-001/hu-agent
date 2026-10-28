package dev.huagent.command;

import dev.huagent.compact.ContextCompactor;
import dev.huagent.compact.MicroCompact;
import dev.huagent.compact.TranscriptStore;
import dev.huagent.goal.GoalCommand;
import dev.huagent.goal.GoalController;
import dev.huagent.model.ChatMessage;
import dev.huagent.tool.ToolDefinition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 会话控制命令：读取或替换主循环的对话历史，以及会话内的清单与目标。 */
final class SessionCommands {
    /** 与 compact 工具的 focus 上限相同。 */
    static final int MAX_FOCUS_CHARS = 200;

    private SessionCommands() {
    }

    static List<SlashCommand> create(CommandSources sources) {
        List<SlashCommand> commands = new ArrayList<>(List.of(exit(), clear(sources), compact(sources),
                context(sources), export(sources)));
        if (sources.goal() != null) {
            commands.add(goal(sources.goal()));
        }
        return commands;
    }

    static SlashCommand exit() {
        return SlashCommand.local("exit", "退出会话，与不带斜杠的 exit / quit 相同",
                (argument, context) -> CommandResult.exit()).withAliases("quit");
    }

    /**
     * 清空前先写完整记录，写不出来就不清空（与 S08 相同：被替换的内容必须能找回）。
     * 只清空主 Agent 的历史、清单与目标；后台命令、队友、计划、记忆与任务文件不受影响。
     */
    static SlashCommand clear(CommandSources sources) {
        return SlashCommand.local("clear", "清空对话（保留系统提示词），同时清空任务清单与当前目标；"
                + "清空前把完整记录写入 " + TranscriptStore.DIRECTORY_NAME, (argument, context) -> {
            List<ChatMessage> history = context.history();
            int messages = history.size() - 1;
            if (messages == 0 && sources.todos().items().isEmpty()
                    && (sources.goal() == null || !sources.goal().active())) {
                return CommandResult.print("清空> 当前对话为空，无需清空。");
            }
            String saved = "";
            if (messages > 0) {
                try {
                    saved = "，完整记录 " + relative(sources.transcripts(), sources.transcripts().save(history));
                } catch (IOException e) {
                    return CommandResult.print("清空> 完整记录写入失败（" + e.getClass().getSimpleName()
                            + "），对话未清空。");
                }
            }
            context.replaceHistory(List.of(history.get(0)));
            boolean hadTodos = !sources.todos().items().isEmpty();
            sources.todos().replace(List.of());
            boolean hadGoal = sources.goal() != null && sources.goal().active();
            if (hadGoal) {
                sources.goal().clear();
            }
            return CommandResult.print("清空> 已清空 " + messages + " 条消息" + saved
                    + (hadTodos ? "，任务清单已清空" : "") + (hadGoal ? "，目标已清除" : "")
                    + "。后台命令、队友、定时计划、记忆与任务文件不受影响，后台结果仍会在下一轮送达。");
        });
    }

    /** 立即总结，不看阈值；总结请求与自动压缩相同，不计入每轮调用上限、不经过 Hook。 */
    static SlashCommand compact(CommandSources sources) {
        return SlashCommand.localWithArgument("compact", "/compact [需要特别保留的内容]",
                "立即总结压缩对话，完整记录写入 " + TranscriptStore.DIRECTORY_NAME, (focus, context) -> {
            if (focus.length() > MAX_FOCUS_CHARS) {
                return CommandResult.print("压缩> 保留说明最多 " + MAX_FOCUS_CHARS + " 个字符，当前 "
                        + focus.length() + " 个，未压缩。");
            }
            ContextCompactor.Prepared prepared = sources.compactor().compactNow(context.history(), focus,
                    context.output());
            if (!prepared.summarized()) {
                return CommandResult.print("压缩> 对话未改变。");
            }
            context.replaceHistory(prepared.history());
            return CommandResult.print("压缩> 对话已替换为系统提示词加一条摘要，下一轮从摘要继续。");
        });
    }

    /** 粗略字符数与 S08 的阈值使用同一算法：不含系统提示词，微压缩之后计算。 */
    static SlashCommand context(CommandSources sources) {
        return SlashCommand.local("context", "查看上下文用量：消息数、粗略字符数与自动压缩阈值", (argument, context) -> {
            List<ChatMessage> history = context.history();
            List<ChatMessage> conversation = history.subList(1, history.size());
            int sent = sources.compactor().contextSize(history);
            int threshold = sources.compactor().thresholdChars();
            long toolChars = 0;
            for (ToolDefinition tool : context.tools()) {
                toolChars += tool.name().length() + tool.description().length()
                        + (tool.parameters() == null ? 0 : tool.parameters().toString().length());
            }
            return CommandResult.print("上下文> 对话 " + conversation.size() + " 条消息（user " + count(conversation, "user")
                    + "、assistant " + count(conversation, "assistant") + "、tool " + count(conversation, "tool")
                    + "），约 " + MicroCompact.size(conversation) + " 字符"
                    + "\n上下文> 发送前微压缩后约 " + sent + " 字符，超过 " + threshold + " 字符自动总结（当前 "
                    + Math.round(sent * 100.0 / threshold) + "%）"
                    + "\n上下文> 系统提示词 " + history.get(0).content().length() + " 字符，工具 " + context.tools().size()
                    + " 个（定义约 " + toolChars + " 字符），这两部分每次请求都会发送，不计入阈值"
                    + "\n上下文> 字符数不是 token 计数，只用于和压缩阈值比较。");
        });
    }

    static SlashCommand export(CommandSources sources) {
        return SlashCommand.local("export", "把当前完整对话写入 " + TranscriptStore.DIRECTORY_NAME + "（JSONL）",
                (argument, context) -> {
            List<ChatMessage> history = context.history();
            if (history.size() <= 1) {
                return CommandResult.print("导出> 当前对话为空，未写入。");
            }
            Path file = sources.transcripts().save(history);
            return CommandResult.print("导出> 已写入 " + relative(sources.transcripts(), file) + "（"
                    + history.size() + " 条消息，含系统提示词）");
        });
    }

    /**
     * S17 的 /goal 并入注册表：查看与清除是本地命令；设置时把条件包装为本轮任务，
     * 经过 UserPromptSubmit 放行后才设置目标，被拒绝时目标不设置，与 S17 行为相同。
     */
    static SlashCommand goal(GoalController goal) {
        return SlashCommand.prompt("goal", "/goal [完成条件 | clear]",
                "带条件时设置目标并开始工作，不带参数查看，clear 清除", (argument, context) -> {
            GoalCommand command;
            try {
                command = GoalCommand.parse(argument.isEmpty() ? GoalCommand.PREFIX
                        : GoalCommand.PREFIX + " " + argument);
            } catch (IllegalArgumentException e) {
                return CommandResult.print("目标> " + e.getMessage());
            }
            if (command.kind() != GoalCommand.Kind.SET) {
                return CommandResult.print(goal.handle(command));
            }
            String condition = command.condition();
            return CommandResult.submit(GoalController.taskPrompt(condition), () -> goal.set(condition),
                    "目标未设置。");
        });
    }

    private static long count(List<ChatMessage> messages, String role) {
        return messages.stream().filter(message -> role.equals(message.role())).count();
    }

    /** 显示为工作目录下的相对路径，如 .transcripts/transcript-….jsonl。 */
    private static String relative(TranscriptStore store, Path file) {
        Path directory = store.directory();
        return file.startsWith(directory) && directory.getParent() != null
                ? directory.getParent().relativize(file).toString().replace('\\', '/') : file.toString();
    }
}
