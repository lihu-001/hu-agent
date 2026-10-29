package dev.huagent.loop;

import dev.huagent.background.BackgroundManager;
import dev.huagent.command.CommandContext;
import dev.huagent.command.CommandRegistry;
import dev.huagent.command.CommandResult;
import dev.huagent.compact.ContextCompactor;
import dev.huagent.goal.GoalCommand;
import dev.huagent.goal.GoalController;
import dev.huagent.goal.GoalDecision;
import dev.huagent.harness.EventHub;
import dev.huagent.harness.ModelRetryPolicy;
import dev.huagent.harness.ToolTrace;
import dev.huagent.harness.Transcript;
import dev.huagent.harness.TurnEvents;
import dev.huagent.harness.TurnInterrupt;
import dev.huagent.hook.AgentHooks;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ContextTooLargeException;
import dev.huagent.model.ModelClient;
import dev.huagent.model.ModelSelection;
import dev.huagent.model.ModelUnavailableException;
import dev.huagent.prompt.PromptStyle;
import dev.huagent.todo.TodoReminder;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ShellTool;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.tool.ToolRegistry;

import cn.hutool.core.util.StrUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ChatLoop {
    private static final String DEFAULT_SYSTEM_PROMPT = "你是一个编程助手，请用中文回答。需要操作或查看环境时使用 shell 工具，"
            + "根据真实工具结果回答。工具错误可修正后再次调用，不要把失败描述为成功。";
    /** 用户中断后记进历史的标记，让模型知道上一轮没做完是用户中断的（Claude Code 为 [Request interrupted by user]）。 */
    static final String INTERRUPTED_MARKER = "[用户中断了本轮请求]";
    static final String TOOL_INTERRUPTED = "工具已中断：用户中断了本轮请求，操作可能已部分完成。";
    static final String TOOL_SKIPPED = "工具未执行：用户中断了本轮请求。";
    /** 每轮最多因空回复重试的次数（借鉴 oh-my-pi 的 empty-stop-retry）。 */
    static final int MAX_EMPTY_RETRIES = 2;
    static final String EMPTY_REPLY_NOTICE = "[程序提示] 你的上一次回复是空的，任务还没完成。"
            + "请继续：发出下一个需要的工具调用，或者给出用户能看到的最终回答。";
    /** 用相同参数连续调用同一工具达到这个次数时提醒（借鉴 oh-my-pi 的 tool-call-loop-redirect）。 */
    static final int REPEATED_CALL_THRESHOLD = 3;
    private final ModelClient client;
    private final ToolRegistry registry;
    private final int maxModelCalls;
    private final String systemPrompt;
    private final AgentHooks hooks;
    private final TodoReminder reminder;
    private final ContextCompactor compactor;
    private final BackgroundManager background;
    /** S15：程序事件中心与限流退避策略；为 null 时与 S14 行为一致。 */
    private final EventHub events;
    private final ModelRetryPolicy retryPolicy;
    /** S17：会话目标的 Goal Gate；为 null 时与 S16 行为一致。 */
    private final GoalController goal;
    /** S18：斜杠命令；为 null 时与 S17 行为一致。 */
    private final CommandRegistry commands;
    /** false 时（hu 默认）不显示启动工具清单、模型请求与耗时、提醒和事件送达说明、工具调用与中间回复，只显示最终回答。 */
    private final boolean debug;
    /** 等待输入时的提示符样式；hu 用 FramedPrompt，其余构造器为 "你> "。 */
    private final PromptStyle prompt;
    /** 用户中断（Esc / Ctrl+C）；为 null 时没有中断，与之前行为一致。 */
    private final TurnInterrupt interrupt;
    /** 回复与工具调用摘要的显示样式；hu 交互终端用 Transcript.marked，其余构造器为 "助手> "、"工具> "。 */
    private final Transcript transcript;
    /** 最近一次主对话请求之后的上下文 token 数，-1 表示未知；只用于提示符显示。 */
    private int contextTokens = -1;

    public ChatLoop(ModelClient client) {
        this(client, new ShellTool(Path.of("playground"), Duration.ofSeconds(30), 12000), 10);
    }

    public ChatLoop(ModelClient client, AgentTool tool, int maxModelCalls) {
        this(client, new ToolRegistry(List.of(tool)), maxModelCalls, DEFAULT_SYSTEM_PROMPT);
    }

    // 支持单工具练习，也可注入多个工具及系统提示词。
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt) {
        this(client, registry, maxModelCalls, systemPrompt, AgentHooks.empty());
    }

    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls,
                    String systemPrompt, AgentHooks hooks) {
        this(client, registry, maxModelCalls, systemPrompt, hooks, null);
    }

    /** S05：reminder 为 null 时与 S04 行为一致。 */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls,
                    String systemPrompt, AgentHooks hooks, TodoReminder reminder) {
        this(client, registry, maxModelCalls, systemPrompt, hooks, reminder, null);
    }

    /** S08：compactor 为 null 时与 S07 行为一致。 */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt,
                    AgentHooks hooks, TodoReminder reminder, ContextCompactor compactor) {
        this(client, registry, maxModelCalls, systemPrompt, hooks, reminder, compactor, null);
    }

    /** S11：background 为 null 时与 S10 行为一致。 */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt,
                    AgentHooks hooks, TodoReminder reminder, ContextCompactor compactor,
                    BackgroundManager background) {
        this(client, registry, maxModelCalls, systemPrompt, hooks, reminder, compactor, background, null, null);
    }

    /**
     * S15：events 提供定时事件与哨兵轮状态，retry 提供限流退避；两者同时为 null 时与 S14 完全相同。
     * retry 为 null 而 events 非 null 时，限流不重试，只做事件唤醒与响应式压缩。
     */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt,
                    AgentHooks hooks, TodoReminder reminder, ContextCompactor compactor,
                    BackgroundManager background, EventHub events, ModelRetryPolicy retryPolicy) {
        this(client, registry, maxModelCalls, systemPrompt, hooks, reminder, compactor, background, events,
                retryPolicy, null);
    }

    /**
     * S17：goal 非 null 时识别 /goal 命令，并在主 Agent 不再调用工具时先经过 Goal Gate：
     * 未完成则把判断理由作为新的用户消息、在同一轮内续跑（共用本轮模型调用上限）；为 null 时与 S16 完全相同。
     */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt,
                    AgentHooks hooks, TodoReminder reminder, ContextCompactor compactor,
                    BackgroundManager background, EventHub events, ModelRetryPolicy retryPolicy,
                    GoalController goal) {
        this(client, registry, maxModelCalls, systemPrompt, hooks, reminder, compactor, background, events,
                retryPolicy, goal, null);
    }

    /**
     * S18：commands 非 null 时识别斜杠命令。本地命令只打印：不请求模型、不触发 Hook、不进入历史；
     * 提示词命令（含 /goal 设置与 /<技能名>）展开为本轮输入，经过 UserPromptSubmit 后提交；
     * 未知命令只提示、不发给模型。为 null 时与 S17 完全相同。
     */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt,
                    AgentHooks hooks, TodoReminder reminder, ContextCompactor compactor,
                    BackgroundManager background, EventHub events, ModelRetryPolicy retryPolicy,
                    GoalController goal, CommandRegistry commands) {
        this(client, registry, maxModelCalls, systemPrompt, hooks, reminder, compactor, background, events,
                retryPolicy, goal, commands, true);
    }

    /** debug 为 false 时只精简终端输出，消息历史与请求内容不变；其余构造器为 true，与之前完全相同。 */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt,
                    AgentHooks hooks, TodoReminder reminder, ContextCompactor compactor,
                    BackgroundManager background, EventHub events, ModelRetryPolicy retryPolicy,
                    GoalController goal, CommandRegistry commands, boolean debug) {
        this(client, registry, maxModelCalls, systemPrompt, hooks, reminder, compactor, background, events,
                retryPolicy, goal, commands, debug, PromptStyle.plain());
    }

    /** prompt 只改变等待输入时终端上的提示符，其余构造器为 "你> "。 */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt,
                    AgentHooks hooks, TodoReminder reminder, ContextCompactor compactor,
                    BackgroundManager background, EventHub events, ModelRetryPolicy retryPolicy,
                    GoalController goal, CommandRegistry commands, boolean debug, PromptStyle prompt) {
        this(client, registry, maxModelCalls, systemPrompt, hooks, reminder, compactor, background, events,
                retryPolicy, goal, commands, debug, prompt, null);
    }

    /**
     * interrupt 非 null 时一轮（含斜杠命令）可被用户中断（hu 里任务运行中按 Esc 或 Ctrl+C，见 TurnInterrupt）：
     * 保留本轮已有的消息，没有结果的工具调用补上中断说明，再记一条中断标记，然后回到提示符；不触发 Stop。
     * 为 null 时与之前完全相同。
     */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt,
                    AgentHooks hooks, TodoReminder reminder, ContextCompactor compactor,
                    BackgroundManager background, EventHub events, ModelRetryPolicy retryPolicy,
                    GoalController goal, CommandRegistry commands, boolean debug, PromptStyle prompt,
                    TurnInterrupt interrupt) {
        this(client, registry, maxModelCalls, systemPrompt, hooks, reminder, compactor, background, events,
                retryPolicy, goal, commands, debug, prompt, interrupt, null);
    }

    /** transcript 只改变回复与工具调用摘要在终端上的样子（见 Transcript）；为 null 时与之前完全相同。 */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt,
                    AgentHooks hooks, TodoReminder reminder, ContextCompactor compactor,
                    BackgroundManager background, EventHub events, ModelRetryPolicy retryPolicy,
                    GoalController goal, CommandRegistry commands, boolean debug, PromptStyle prompt,
                    TurnInterrupt interrupt, Transcript transcript) {
        if (maxModelCalls <= 0) {
            throw new IllegalArgumentException("每轮模型调用上限必须为正数");
        }
        this.client = client;
        this.registry = registry;
        this.maxModelCalls = maxModelCalls;
        this.systemPrompt = systemPrompt;
        this.hooks = Objects.requireNonNull(hooks);
        this.reminder = reminder;
        this.compactor = compactor;
        this.background = background;
        this.events = events;
        this.retryPolicy = retryPolicy;
        this.goal = goal;
        this.commands = commands;
        this.debug = debug;
        this.prompt = Objects.requireNonNull(prompt);
        this.interrupt = interrupt;
        this.transcript = transcript == null ? Transcript.plain() : transcript;
    }

    public void run(BufferedReader input, PrintWriter output) throws IOException {
        try {
            converse(input, output);
        } finally {
            if (interrupt != null) {
                interrupt.end();
            }
            // S11：退出、EOF、中断或异常结束会话时都取消仍在运行的后台命令。
            if (background != null) {
                int cancelled = background.shutdown();
                if (cancelled > 0) {
                    output.println("后台> 会话结束，已取消 " + cancelled + " 个仍在运行的后台任务。");
                    output.flush();
                }
            }
        }
    }

    private void converse(BufferedReader input, PrintWriter output) throws IOException {
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("system", systemPrompt));
        List<ToolDefinition> definitions = registry.definitions();
        // 非 debug 时由 hu 的启动横幅说明用法，工具清单可用 /tools 查看。
        if (debug) {
            output.println("对话已启动，输入 exit 或 quit 退出。每次输入可触发多次工具调用。"
                    + (commands == null ? "" : "输入 /help 查看斜杠命令。"));
            for (ToolDefinition definition : definitions) {
                output.println("工具> " + definition.name() + "：" + definition.description());
            }
        }
        while (true) {
            // 上一行的处理到此结束：不再响应中断按键，没处理完的中断请求作废。
            if (interrupt != null) {
                interrupt.end();
            }
            if (client instanceof ModelSelection selection) {
                prompt.model(selection.model());
            }
            prompt.context(contextTokens);
            prompt.begin(output);
            output.flush();
            String line = input.readLine();
            prompt.end(output, line);
            output.flush();
            if (line == null || "exit".equalsIgnoreCase(line.strip()) || "quit".equalsIgnoreCase(line.strip())) {
                output.println("对话已结束。");
                output.flush();
                return;
            }
            // S15：程序事件的哨兵行不是用户输入，不记录消息、不触发 UserPromptSubmit。
            boolean synthetic = events != null && TurnEvents.isWake(line);
            if (StrUtil.isBlank(line)) {
                continue;
            }
            // 从这里到下一次提示符都是任务（含斜杠命令），用户可以中断。
            if (interrupt != null) {
                interrupt.begin();
            }
            transcript.startTurn();
            // 命令提交的本轮输入在 UserPromptSubmit 放行后才生效（如设置目标），被拒绝时在提示后追加说明。
            Supplier<String> onAccepted = null;
            String rejectedNote = "";
            if (commands != null && !synthetic) {
                // S18：斜杠命令（含 /goal）由注册表处理；不是命令形式的输入照常作为提示词。
                CommandContext context = new CommandContext(history, definitions, output);
                String previousModel = client instanceof ModelSelection selection ? selection.model() : null;
                CommandResult result;
                try {
                    result = commands.dispatch(line, context);
                } catch (IOException e) {
                    result = CommandResult.print("命令> 执行失败（" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : "：" + e.getMessage()) + "）");
                } catch (InterruptedException e) {
                    if (userInterrupted()) {
                        print(output, "命令> 已中断。");
                        continue;
                    }
                    Thread.currentThread().interrupt();
                    output.println("请求已中断，对话结束。");
                    output.flush();
                    return;
                }
                // /clear 与 /compact 替换了历史：此后的轮次从新历史开始，上一次请求的 token 用量不再适用。
                if (context.historyReplaced()) {
                    history = new ArrayList<>(context.history());
                    contextTokens = -1;
                }
                // 切换模型保留历史，但旧模型的 token 计数不适用于新模型。
                if (client instanceof ModelSelection selection && !selection.model().equals(previousModel)) {
                    contextTokens = -1;
                }
                if (result != null && result.kind() == CommandResult.Kind.EXIT) {
                    output.println("对话已结束。");
                    output.flush();
                    return;
                }
                if (result != null && result.kind() == CommandResult.Kind.PRINT) {
                    print(output, result.text());
                    continue;
                }
                if (result != null) {
                    line = result.text();
                    onAccepted = result.onAccepted();
                    rejectedNote = result.rejectedNote();
                }
            } else if (goal != null && !synthetic) {
                // S17：/goal 是会话命令：查看与清除只打印、不请求模型；设置时把完成条件作为本轮任务提交。
                GoalCommand command;
                try {
                    command = GoalCommand.parse(line);
                } catch (IllegalArgumentException e) {
                    output.println("目标> " + e.getMessage());
                    continue;
                }
                if (command != null && command.kind() != GoalCommand.Kind.SET) {
                    output.println(goal.handle(command));
                    continue;
                }
                if (command != null) {
                    String condition = command.condition();
                    line = GoalController.taskPrompt(condition);
                    onAccepted = () -> goal.set(condition);
                    rejectedNote = "目标未设置。";
                }
            }

            // 首次请求失败仍丢弃本轮；工具执行后保留完整协议历史，操作不能靠丢弃消息撤销。
            List<ChatMessage> request = new ArrayList<>(history);
            int turnStart = request.size();
            boolean toolsRecorded = false;
            // 借鉴 oh-my-pi 对较弱模型的纠偏：本轮空回复的重试次数；上一个工具调用与连续相同的次数。
            int emptyRetries = 0;
            String previousCall = null;
            int sameCallCount = 0;
            // 正在执行的工具调用：本轮被中断时它的结果记为已中断，之后的记为未执行。
            ToolCall running = null;
            // S11：本轮未保存前交付的通知；首次请求失败、本轮被丢弃时放回队列。
            List<String> unsavedScheduled = new ArrayList<>();
            List<String> unsavedBackground = new ArrayList<>();
            try {
                if (!synthetic) {
                    // S04：过滤退出/空输入后，先触发钩子，再记录用户消息。
                    String blocked = hooks.userPromptSubmit(line, history);
                    if (blocked != null) {
                        output.println("钩子拒绝：" + blocked + " 提示词未记录，未请求模型。" + rejectedNote);
                        output.flush();
                        continue;
                    }
                    if (onAccepted != null) {
                        String note = onAccepted.get();
                        if (note != null) {
                            output.println(note);
                        }
                    }
                    request.add(new ChatMessage("user", line));
                }
                if (reminder != null) {
                    reminder.startTurn();
                }
                if (goal != null) {
                    goal.startTurn();
                }
                // S15：哨兵轮标记给 PermissionGate，程序自动发起的轮次拒绝交互确认。
                if (events != null) {
                    events.setSyntheticTurn(synthetic);
                }
                try {
                    for (int step = 1; step <= maxModelCalls; step++) {
                        checkInterrupt();
                        // S15：定时事件与后台完成通知合并附加到最后一条 user/tool 消息末尾，不新增消息角色。
                        List<String> scheduled = events == null ? List.of() : events.drain();
                        List<String> notices = background == null ? List.of() : background.takeNotifications();
                        if (scheduled.isEmpty() && notices.isEmpty()) {
                            if (synthetic && !toolsRecorded) {
                                // 唤醒后事件已被取走：无事可做，回到等待输入，不空请求模型。
                                break;
                            }
                        } else {
                            if (!toolsRecorded) {
                                unsavedScheduled.addAll(scheduled);
                                unsavedBackground.addAll(notices);
                            }
                            if (synthetic) {
                                // 哨兵轮没有本轮的 user/tool 消息，事件作为一条新的用户消息进入。
                                request.add(new ChatMessage("user", renderNotices(scheduled, notices)));
                            } else {
                                appendNotices(request, scheduled, notices);
                            }
                            detail(output, scheduled.isEmpty() ? "后台> 已将完成结果交给模型"
                                    : "事件> 已将程序事件交给模型");
                        }
                        // 本轮最后一次请求：先告诉模型工具不会再执行，让它总结进展和剩余步骤，
                        // 而不是让最后一批工具调用白白丢掉、用户什么也看不到。
                        boolean lastCall = step == maxModelCalls && toolsRecorded;
                        if (lastCall) {
                            appendToLast(request, limitNotice());
                        }
                        // 非流式请求在完整响应前没有输出，先提示等待并在返回后报告耗时。
                        detail(output, "模型请求> 第 " + step + " 次请求中...");
                        List<ChatMessage> sent = request;
                        if (compactor != null) {
                            // S08：请求前整理上下文；总结后本轮之前的内容都在摘要里，本轮从摘要消息算起。
                            ContextCompactor.Prepared prepared = compactor.beforeRequest(List.copyOf(request), output);
                            if (prepared.summarized()) {
                                request = new ArrayList<>(prepared.history());
                                turnStart = request.size() - 1;
                                if (toolsRecorded) {
                                    history = request;
                                }
                            }
                            sent = prepared.sent();
                        }
                        long startNanos = System.nanoTime();
                        Recovery recovery = completeWithRecovery(sent, request, definitions, output);
                        if (recovery.summarized()) {
                            // 响应式压缩后的历史成为新的对话状态，本轮从摘要消息算起。
                            request = new ArrayList<>(recovery.history());
                            turnStart = request.size() - 1;
                            if (toolsRecorded) {
                                history = request;
                            }
                            sent = recovery.sent();
                        }
                        ChatMessage answer = recovery.answer();
                        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
                        detail(output, "模型响应> 耗时 " + elapsedMillis + " ms，工具调用 "
                                + answer.toolCalls().size() + " 个");
                        request.add(answer);
                        // 与 Claude Code 相同，与工具调用一起返回的中间说明也显示，用户能看到模型打算做什么。
                        if (StrUtil.isNotBlank(answer.content())) {
                            print(output, out -> transcript.reply(out, answer.content()));
                        }
                        if (answer.toolCalls().isEmpty() && StrUtil.isBlank(answer.content()) && !synthetic
                                && step < maxModelCalls && emptyRetries < MAX_EMPTY_RETRIES) {
                            // 空回复（没有文字也没有工具调用）多半是模型中途停住了：不记录这条空消息，
                            // 提醒附在上一条消息末尾（不新增消息角色），再请求一次。
                            request.removeLast();
                            appendToLast(request, EMPTY_REPLY_NOTICE);
                            emptyRetries++;
                            detail(output, "提示> 模型返回了空回复，已提醒它继续");
                            continue;
                        }
                        if (answer.toolCalls().isEmpty()) {
                            history = request;
                            if (lastCall && (goal == null || !goal.active())) {
                                output.println("提示> 本轮已用完 " + maxModelCalls + " 次模型调用；输入“继续”接着做。");
                            }
                            output.flush();
                            // S17：有活跃目标时先由独立判断器决定本轮能否结束（Stop 之前的 Goal Gate）。
                            GoalDecision decision = goal == null ? GoalDecision.ALLOW
                                    : goal.afterTurn(request, output);
                            if (decision.action() == GoalDecision.Action.BLOCK) {
                                // 哨兵轮中用户可能不在终端，需要确认的操作会被自动拒绝，不在这里续跑。
                                if (!synthetic && step < maxModelCalls) {
                                    request.add(new ChatMessage("user", goal.continuation(decision)));
                                    // 本轮已保存进历史：之后的请求失败不再丢弃本轮，已送达的通知也不放回。
                                    toolsRecorded = true;
                                    continue;
                                }
                                output.println(synthetic
                                        ? "目标> 程序事件轮不自动续跑，目标保持活跃；输入任意内容继续。"
                                        : "目标> 已达到本轮模型调用上限，不再续跑，目标保持活跃；输入任意内容继续。");
                            }
                            hooks.stop(request.subList(turnStart, request.size()));
                            break;
                        }
                        boolean limitReached = step == maxModelCalls;
                        for (ToolCall call : answer.toolCalls()) {
                            checkInterrupt();
                            detail(output, ToolTrace.request(call));
                            // 非 debug 时每次工具调用显示一段摘要（调用了什么、结果如何），完整结果只给模型；
                            // 执行前先与上一段隔开，执行中弹出的权限确认与摘要同属一段。
                            boolean summarize = !debug && !limitReached;
                            if (summarize) {
                                print(output, transcript::beforeTool);
                            }
                            running = call;
                            String result = limitReached ? "工具未执行：已达到本轮模型调用上限。"
                                    : registry.execute(call);
                            running = null;
                            String signature = call.name() + "\u0000" + call.arguments();
                            sameCallCount = signature.equals(previousCall) ? sameCallCount + 1 : 1;
                            previousCall = signature;
                            // 用相同参数反复调用同一个工具，结果不会变：提醒模型换个做法，避免空转到调用上限。
                            String recorded = sameCallCount >= REPEATED_CALL_THRESHOLD && !limitReached
                                    ? result + "\n\n" + repeatedCallNotice(call.name(), sameCallCount) : result;
                            request.add(ChatMessage.tool(call.id(), recorded));
                            detail(output, ToolTrace.result(call, recorded));
                            if (summarize) {
                                print(output, out -> transcript.tool(out, call, result));
                            }
                        }
                        // S05：提醒附加在本批最后一个工具结果后，不新增消息角色，协议顺序不变。
                        String nudge = reminder == null || limitReached ? null : reminder.afterToolBatch();
                        if (nudge != null) {
                            int last = request.size() - 1;
                            ChatMessage tool = request.get(last);
                            request.set(last, ChatMessage.tool(tool.toolCallId(), tool.content() + "\n\n" + nudge));
                            detail(output, "提醒> " + nudge);
                        }
                        history = request;
                        toolsRecorded = true;
                        if (limitReached) {
                            String reason = "已达到本轮模型调用上限（" + maxModelCalls + " 次），停止本轮；最后一批工具未执行。";
                            request.add(new ChatMessage("assistant", reason));
                            print(output, out -> transcript.reply(out, reason));
                            if (goal != null && goal.active()) {
                                output.println("目标> 目标保持活跃；输入任意内容继续推进。");
                            }
                        }
                    }
                } finally {
                    if (events != null) {
                        events.setSyntheticTurn(false);
                    }
                }
            } catch (HttpTimeoutException e) {
                requeue(toolsRecorded, unsavedScheduled, unsavedBackground);
                output.println("请求超时；" + failureHint(toolsRecorded));
            } catch (IOException e) {
                requeue(toolsRecorded, unsavedScheduled, unsavedBackground);
                output.println("请求失败：" + e.getMessage() + "；" + failureHint(toolsRecorded));
            } catch (InterruptedException e) {
                if (!userInterrupted()) {
                    Thread.currentThread().interrupt();
                    output.println("请求已中断，对话结束。");
                    output.flush();
                    return;
                }
                // 与 Claude Code 相同，中断的一轮留在历史里（已送达的通知随之保存）；提示词还没记录时没有可保留的。
                if (request.size() > turnStart) {
                    closeInterrupted(request, running);
                    history = request;
                    output.println("已中断 · 接下来要做什么？");
                    if (goal != null && goal.active()) {
                        output.println("目标> 目标保持活跃；输入任意内容继续推进。");
                    }
                } else {
                    output.println("已中断 · 提示词未提交。");
                }
            }
            output.flush();
        }
    }

    /** 检查点：按键时任务可能不在可中断的阻塞调用里，或者工具没有把中断传出来，在这里结束本轮。 */
    private void checkInterrupt() throws InterruptedException {
        if (interrupt != null && interrupt.requested()) {
            throw new InterruptedException("用户中断了本轮请求");
        }
    }

    /** 是不是用户中断（而不是会话结束）；是的话清除中断请求留下的线程中断状态，之后的输出与保存照常进行。 */
    private boolean userInterrupted() {
        if (interrupt == null || !interrupt.requested()) {
            return false;
        }
        Thread.interrupted();
        return true;
    }

    /**
     * 中断后补全本轮：协议要求每个工具调用都有结果，最后一条回复里还没有结果的调用补上说明
     * （正在执行的为已中断，其余为未执行）；最后记一条中断标记。
     */
    private static void closeInterrupted(List<ChatMessage> request, ToolCall running) {
        int last = request.size() - 1;
        while (last >= 0 && !"assistant".equals(request.get(last).role())) {
            last--;
        }
        if (last >= 0) {
            Set<String> answered = new HashSet<>();
            for (ChatMessage message : request.subList(last + 1, request.size())) {
                if ("tool".equals(message.role())) {
                    answered.add(message.toolCallId());
                }
            }
            for (ToolCall call : request.get(last).toolCalls()) {
                if (!answered.contains(call.id())) {
                    request.add(ChatMessage.tool(call.id(), call.equals(running) ? TOOL_INTERRUPTED : TOOL_SKIPPED));
                }
            }
        }
        request.add(new ChatMessage("user", INTERRUPTED_MARKER));
    }

    /**
     * S15：限流（HTTP 429/503/529）按 Retry-After 或指数退避重试，超过次数上限后按普通失败处理；
     * 上下文超长先响应式压缩一次再重试，压缩不出摘要时按普通失败处理。其余失败原样抛出。
     * summarized 为 true 表示对话历史已被替换为摘要，调用方需要采纳新的历史。
     */
    private Recovery completeWithRecovery(List<ChatMessage> sent, List<ChatMessage> request,
                                          List<ToolDefinition> definitions, PrintWriter output)
            throws IOException, InterruptedException {
        int attempt = 0;
        boolean reactiveCompacted = false;
        while (true) {
            try {
                // 响应不带 usage 时用量记为未知，不沿用上一次请求的数字。
                contextTokens = -1;
                return new Recovery(client.complete(List.copyOf(sent), definitions, tokens -> contextTokens = tokens),
                        request, sent, false);
            } catch (ModelUnavailableException e) {
                attempt++;
                if (retryPolicy == null || attempt > retryPolicy.maxRetries()) {
                    throw e;
                }
                long delay = retryPolicy.delayMillis(attempt, e.retryAfterSeconds());
                output.println("模型请求> " + e.getMessage() + "，等待 " + (delay / 1000)
                        + " 秒后第 " + attempt + " 次重试...");
                output.flush();
                retryPolicy.sleep(delay);
            } catch (ContextTooLargeException e) {
                if (compactor == null || reactiveCompacted) {
                    throw e;
                }
                output.println("压缩> " + e.getMessage() + "，保存完整记录并请求模型总结...");
                output.flush();
                // 响应式压缩作用于对话历史本身，而不是只影响本次发送的微压缩视图。
                ContextCompactor.Prepared prepared = compactor.beforeRequest(request, output, true);
                if (!prepared.summarized()) {
                    throw e;
                }
                sent = prepared.sent();
                request = prepared.history();
                reactiveCompacted = true;
            }
        }
    }

    /** 一次模型请求的结果；summarized 时 history/sent 是压缩后的新状态。 */
    private record Recovery(ChatMessage answer, List<ChatMessage> history, List<ChatMessage> sent,
                            boolean summarized) {
    }

    /** 程序事件与后台完成合并渲染，定时事件在前。 */
    private static String renderNotices(List<String> scheduled, List<String> notices) {
        StringBuilder extra = new StringBuilder();
        if (!scheduled.isEmpty()) {
            extra.append(EventHub.render(scheduled));
        }
        if (!notices.isEmpty()) {
            if (extra.length() > 0) {
                extra.append("\n\n");
            }
            extra.append(BackgroundManager.render(notices));
        }
        return extra.toString();
    }

    /** 程序事件与后台完成合并附加到最后一条消息末尾，定时事件在前。 */
    private static void appendNotices(List<ChatMessage> request, List<String> scheduled, List<String> notices) {
        appendToLast(request, renderNotices(scheduled, notices));
    }

    static String repeatedCallNotice(String tool, int count) {
        return "[程序提示] 你已经连续 " + count + " 次用完全相同的参数调用 " + tool + "，结果不会变。"
                + "本轮不要再这样调用：换参数、换工具，或者根据已有结果给出结论。";
    }

    /** 本轮最后一次请求附加的说明；模型照常可以调用工具，但这批调用不会执行。 */
    private String limitNotice() {
        return "[程序提示] 本轮已用到第 " + maxModelCalls + " 次模型调用（上限），这是本轮最后一次请求，"
                + "再调用工具也不会执行。请直接用文字说明已完成的工作、目前的发现，以及还剩哪些步骤；用户回复后可以接着做。";
    }

    /** 附加到最后一条 user/tool 消息末尾，不新增消息角色、不改变协议顺序；消息没有文本时直接以附加内容开头。 */
    private static void appendToLast(List<ChatMessage> request, String extra) {
        int last = request.size() - 1;
        ChatMessage message = request.get(last);
        String base = message.content() == null ? "" : message.content();
        String content = base.isEmpty() ? extra : base + "\n\n" + extra;
        request.set(last, "tool".equals(message.role())
                ? ChatMessage.tool(message.toolCallId(), content)
                : new ChatMessage(message.role(), content));
    }

    private void requeue(boolean toolsRecorded, List<String> unsavedScheduled, List<String> unsavedBackground) {
        if (toolsRecorded) {
            return;
        }
        if (!unsavedScheduled.isEmpty() && events != null) {
            events.requeue(unsavedScheduled);
        }
        if (!unsavedBackground.isEmpty() && background != null) {
            background.requeue(unsavedBackground);
        }
    }

    private String failureHint(boolean toolsRecorded) {
        return toolsRecorded ? "已保留工具调用和结果，已执行的操作不会回滚，请输入下一步。"
                : "本轮未保存，请重新输入。";
    }

    /** 只在 debug 模式显示的运行细节。 */
    private void detail(PrintWriter output, String line) {
        if (debug) {
            output.println(line);
            output.flush();
        }
    }

    /** 命令输出可能有多行，团队模式下队友也在写同一终端，整段加锁输出。 */
    private static void print(PrintWriter output, String text) {
        print(output, out -> out.println(text));
    }

    private static void print(PrintWriter output, Consumer<PrintWriter> block) {
        synchronized (output) {
            block.accept(output);
            output.flush();
        }
    }
}
