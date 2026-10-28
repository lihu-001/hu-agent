package dev.huagent.command;

import dev.huagent.compact.ContextCompactor;
import dev.huagent.compact.MicroCompact;
import dev.huagent.compact.TranscriptStore;
import dev.huagent.config.ModelDefaults;
import dev.huagent.goal.GoalCommand;
import dev.huagent.goal.GoalController;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelSelection;
import dev.huagent.permission.AutoApprove;
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

    /** defaults 为 null 时 /model 只在本次会话切换，见 model；autoApprove 为 null 时（教程入口）没有 /auto。 */
    static List<SlashCommand> create(CommandSources sources, ModelDefaults defaults, AutoApprove autoApprove) {
        List<SlashCommand> commands = new ArrayList<>(List.of(exit(), model(sources, defaults), clear(sources),
                compact(sources), context(sources), export(sources)));
        if (sources.goal() != null) {
            commands.add(goal(sources.goal()));
        }
        if (autoApprove != null) {
            commands.add(autoApprove(autoApprove));
        }
        return commands;
    }

    static SlashCommand exit() {
        return SlashCommand.local("exit", "退出会话，与不带斜杠的 exit / quit 相同",
                (argument, context) -> CommandResult.exit()).withAliases("quit");
    }

    /**
     * 不带参数时列出可选模型：当前模型、常用模型（配置的 models）与接口 /models 返回的模型；带名称时切换同一接口的
     * 模型并保留对话，共用客户端的子代理、队友、压缩与默认目标判断器从下一次请求起跟着换。交互终端里输入
     * "/model " 后行编辑器列出这些模型（见 ModelCatalog），↑↓ 选择。
     * defaults 不为 null（hu）时，能确认存在的模型写回启动时读取模型的配置文件，下次启动沿用；
     * 为 null（教程入口）时只在本次会话生效。
     */
    static SlashCommand model(CommandSources sources, ModelDefaults defaults) {
        String provider = sources.config().modelConfig().provider().label();
        if (!(sources.client() instanceof ModelSelection selection)) {
            return SlashCommand.localWithArgument("model", "/model", "查看当前模型", (argument, context) ->
                    CommandResult.print(argument.isEmpty()
                            ? "模型> 当前模型 " + sources.currentModel() + "（" + provider + "）"
                            : "模型> 当前客户端不支持会话内切换模型，未切换。"));
        }
        ModelCatalog catalog = new ModelCatalog(selection, sources.config().modelConfig().models());
        catalog.prefetch();
        return SlashCommand.localWithArgument("model", "/model [模型名称]",
                "列出可选模型，或切换同一接口的模型" + (defaults == null ? "（仅本次会话）" : "并设为默认"),
                (argument, context) -> CommandResult.print(argument.isEmpty() ? listing(catalog, provider, defaults)
                        : select(catalog, defaults, argument)))
                .withChoices(catalog::choices);
    }

    private static String listing(ModelCatalog catalog, String provider, ModelDefaults defaults)
            throws InterruptedException {
        StringBuilder text = new StringBuilder("模型> 当前模型 ").append(catalog.selection().model())
                .append("（").append(provider).append("）");
        if (!catalog.favorites().isEmpty()) {
            text.append("\n模型> 常用 ").append(String.join("、", catalog.favorites()));
        }
        try {
            List<String> available = catalog.available();
            text.append("\n模型> 接口提供 ").append(available.size()).append(" 个")
                    .append(available.isEmpty() ? "" : "：" + String.join("、", available));
        } catch (IOException e) {
            text.append("\n模型> 接口没有返回模型列表（").append(reason(e)).append("）")
                    .append(catalog.favorites().isEmpty() ? "，可在配置文件的 models 中列出常用模型" : "");
        }
        return text.append("\n模型> /model <名称> 切换").append(defaults == null ? "（仅本次会话）" : "并设为默认")
                .append("；交互终端里输入 \"/model \" 后可用 ↑↓ 选择。").toString();
    }

    private static String select(ModelCatalog catalog, ModelDefaults defaults, String name)
            throws InterruptedException {
        ModelSelection selection = catalog.selection();
        String previous = selection.model();
        try {
            selection.selectModel(name);
        } catch (IllegalArgumentException e) {
            return "模型> 模型名称必须是一个不含空白、控制字符或不可见字符的名称，未切换。用法：/model <模型名称>";
        }
        if (previous.equals(name)) {
            return "模型> 已在使用 " + previous + "，无需切换。";
        }
        return "模型> 已从 " + previous + " 切换到 " + name + "，保留当前对话；子代理、队友、上下文压缩与默认目标判断器"
                + "也从下一次请求起使用它。\n模型> " + persist(catalog, defaults, name);
    }

    /** 只把能确认存在的模型（常用模型或接口列表中有）写成默认，拼错的名字不会留到下次启动。 */
    private static String persist(ModelCatalog catalog, ModelDefaults defaults, String name)
            throws InterruptedException {
        if (defaults == null) {
            return "仅本次会话生效。";
        }
        if (defaults.file() == null) {
            return "环境变量 HU_MODEL 优先于配置文件，仅本次会话生效。";
        }
        if (!catalog.favorites().contains(name)) {
            try {
                if (!catalog.available().contains(name)) {
                    return "接口的模型列表里没有 " + name + "，仅本次会话生效，未设为默认；下一次请求会确认它是否可用。";
                }
            } catch (IOException e) {
                return "接口没有返回模型列表（" + reason(e) + "），无法确认 " + name + " 是否存在，仅本次会话生效；"
                        + "确认可用后可把它写进配置文件的 models 或 model。";
            }
        }
        try {
            defaults.save(name);
            return "已设为默认，写入 " + defaults.file() + "。";
        } catch (IOException e) {
            return "默认模型写入失败（" + e.getClass().getSimpleName() + "），仅本次会话生效。";
        }
    }

    /**
     * 切换自动审批模式（见 AutoApprove）：不带参数在开、关之间切换，on / off 直接指定。
     * 从下一次权限检查起生效，正在等待回答的确认不受影响；不请求模型，也不改变对话。
     */
    static SlashCommand autoApprove(AutoApprove autoApprove) {
        return SlashCommand.localWithArgument("auto", "/auto [on | off]",
                "自动审批：需要确认的工具调用自动批准，硬拒绝仍生效；不带参数切换开关", (argument, context) -> {
            boolean enable;
            switch (argument) {
                case "" -> enable = !autoApprove.enabled();
                case "on" -> enable = true;
                case "off" -> enable = false;
                default -> {
                    return CommandResult.print("审批> 用法：/auto [on | off]，不带参数时切换开关。");
                }
            }
            if (enable == autoApprove.enabled()) {
                return CommandResult.print("审批> 已经" + (enable ? "开启" : "关闭") + "，无需切换。");
            }
            autoApprove.set(enable);
            return CommandResult.print(enable
                    ? "审批> 已开启：需要确认的工具调用（Shell、写文件、MCP 工具等）自动批准并显示一行记录，"
                            + "子代理、队友与程序事件发起的轮次也一样；硬拒绝规则与工作目录检查仍然生效。/auto off 关闭。"
                    : "审批> 已关闭：需要确认的工具调用恢复逐次询问。");
        }).withChoices(() -> List.of(new SlashCommand.Choice("on", "开启：权限确认自动批准"),
                new SlashCommand.Choice("off", "关闭：恢复逐次确认")));
    }

    /** 失败原因只取异常消息（ModelHttp 的消息不含响应正文），没有消息时用异常类名。 */
    private static String reason(IOException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
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
