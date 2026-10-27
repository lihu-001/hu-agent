package dev.huagent.compact;

import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.ToolCall;

import cn.hutool.core.util.StrUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 每次请求模型前整理上下文，三层：
 * 1. 微压缩：较早的长工具结果换成占位说明，只影响本次发送的内容，不请求模型；
 * 2. 自动压缩：微压缩后对话（不含系统提示词）的粗略字符数仍超过阈值时，先保存完整记录，再请模型总结，历史换成“系统提示词 + 摘要”；
 * 3. 手动压缩：模型调用 compact 后，在本批工具结果补齐、下一次请求前执行同样的总结。
 * 总结请求不带工具，不计入每轮模型调用上限，也不经过 Hook。
 */
public final class ContextCompactor {
    public static final int MAX_SUMMARY_INPUT_CHARS = 60000;
    public static final int MAX_SUMMARY_CHARS = 8000;
    public static final String SUMMARY_MARKER = "[对话已压缩]";
    public static final String SUMMARIZER_PROMPT = "你负责压缩一段编程助手与用户的对话，让助手只凭摘要就能继续工作。"
            + "用中文分条写出：用户的目标与明确要求；已完成的操作与关键结果（文件路径、命令、结论）；"
            + "当前进展、未完成事项与下一步；用户的约束和偏好。只依据对话记录，不要编造，不要输出客套话。";

    private final ModelClient client;
    private final TranscriptStore store;
    private final Path workDirectory;
    private final int thresholdChars;
    private final int keepRecentToolResults;
    private final Supplier<String> state;
    /** false 时（hu 默认）不显示微压缩说明；自动、手动与响应式总结照常显示。 */
    private final boolean debug;
    private boolean pending;
    private String pendingFocus;
    private int reportedMicro;

    /** state 提供压缩后需原样保留的程序状态（如任务清单），返回空白或为 null 时不附加。 */
    public ContextCompactor(ModelClient client, TranscriptStore store, Path workDirectory, int thresholdChars,
                            int keepRecentToolResults, Supplier<String> state) {
        this(client, store, workDirectory, thresholdChars, keepRecentToolResults, state, true);
    }

    public ContextCompactor(ModelClient client, TranscriptStore store, Path workDirectory, int thresholdChars,
                            int keepRecentToolResults, Supplier<String> state, boolean debug) {
        if (thresholdChars <= 0 || keepRecentToolResults < 0) {
            throw new IllegalArgumentException("压缩阈值必须为正数，保留的工具结果数不能为负");
        }
        this.client = Objects.requireNonNull(client);
        this.store = Objects.requireNonNull(store);
        this.workDirectory = Objects.requireNonNull(workDirectory);
        this.thresholdChars = thresholdChars;
        this.keepRecentToolResults = keepRecentToolResults;
        this.state = state;
        this.debug = debug;
    }

    public int thresholdChars() {
        return thresholdChars;
    }

    /** compact 工具只登记请求，等本批工具结果都补齐后再总结，避免打断 tool_call 协议。 */
    public synchronized void requestCompaction(String focus) {
        pending = true;
        pendingFocus = StrUtil.isBlank(focus) ? null : focus.strip();
    }

    public synchronized boolean pending() {
        return pending;
    }

    /**
     * history：调用方之后保存的历史；sent：本次实际发给模型的消息。
     * summarized 为 true 表示历史已被替换为摘要，只剩系统提示词和一条摘要用户消息。
     */
    public record Prepared(List<ChatMessage> history, List<ChatMessage> sent, boolean summarized) {
    }

    public Prepared beforeRequest(List<ChatMessage> history, PrintWriter output) throws InterruptedException {
        return beforeRequest(history, output, false);
    }

    /**
     * S15：force 为 true 时不看阈值，用于模型返回上下文超长后的响应式压缩；
     * 手动登记与自动阈值的行为不变。没有可总结内容时返回 summarized=false，由调用方决定回退。
     */
    public Prepared beforeRequest(List<ChatMessage> history, PrintWriter output, boolean force)
            throws InterruptedException {
        List<ChatMessage> micro = MicroCompact.apply(history, keepRecentToolResults);
        int replaced = 0;
        for (int i = 0; i < micro.size(); i++) {
            if (micro.get(i) != history.get(i)) {
                replaced++;
            }
        }
        if (debug && replaced > reportedMicro) {
            print(output, "压缩> 微压缩：本次请求中 " + replaced + " 个较早的工具结果以占位说明代替");
        }
        reportedMicro = replaced;
        String focus;
        String reason;
        synchronized (this) {
            reason = pending ? "模型请求压缩" : null;
            focus = pendingFocus;
            pending = false;
            pendingFocus = null;
        }
        // 系统提示词是固定开销，压缩也无法缩小，不计入阈值，否则阈值较小时会在摘要后反复压缩。
        int size = conversationSize(micro);
        if (reason == null && force) {
            reason = "上下文超长";
        } else if (reason == null && size > thresholdChars) {
            reason = "对话约 " + size + " 字符，超过阈值 " + thresholdChars;
        }
        if (reason == null) {
            return new Prepared(history, micro, false);
        }
        return summarizeNow(history, micro, size, reason, focus, output);
    }

    /**
     * S18：/compact 命令立即总结，不看阈值。命令只在两轮之间执行，不会打断 tool_call 协议，
     * 因此不走登记流程；focus 为空白时不附加保留要求。没有可总结内容或总结失败时返回 summarized=false，历史不变。
     */
    public Prepared compactNow(List<ChatMessage> history, String focus, PrintWriter output)
            throws InterruptedException {
        List<ChatMessage> micro = MicroCompact.apply(history, keepRecentToolResults);
        return summarizeNow(history, micro, conversationSize(micro), "用户执行 /compact",
                StrUtil.isBlank(focus) ? null : focus.strip(), output);
    }

    private Prepared summarizeNow(List<ChatMessage> history, List<ChatMessage> micro, int size, String reason,
                                  String focus, PrintWriter output) throws InterruptedException {
        if (summarizable(micro) == 0) {
            print(output, "压缩> " + reason + "，但没有可总结的历史，跳过");
            return new Prepared(history, micro, false);
        }
        print(output, "压缩> " + reason + "，保存完整记录并请求模型总结...");
        try {
            List<ChatMessage> compacted = summarize(history, micro, focus);
            reportedMicro = 0;
            print(output, "压缩> 完成：对话约 " + size + " → " + conversationSize(compacted) + " 字符");
            return new Prepared(compacted, compacted, true);
        } catch (IOException e) {
            // 保存或总结失败不丢弃历史，继续用微压缩后的上下文请求。
            print(output, "压缩> 失败（" + e.getClass().getSimpleName() + "），继续使用未总结的上下文");
            return new Prepared(history, micro, false);
        }
    }

    private List<ChatMessage> summarize(List<ChatMessage> history, List<ChatMessage> micro, String focus)
            throws IOException, InterruptedException {
        // 记录保存完整原文；记录写不出来就不总结，否则被替换的内容将无从找回。
        Path file = store.save(history);
        boolean hasSystem = "system".equals(micro.get(0).role());
        // 尚未得到回复的最新用户消息不交给模型总结；最近一条真实用户输入始终原样附在摘要后。
        boolean lastIsUser = "user".equals(micro.get(micro.size() - 1).role());
        List<ChatMessage> body = micro.subList(hasSystem ? 1 : 0, lastIsUser ? micro.size() - 1 : micro.size());
        String latestInput = latestUserInput(micro);
        String instruction = focus == null ? "" : "\n本次压缩请特别保留：" + focus;
        ChatMessage answer = client.complete(List.of(new ChatMessage("system", SUMMARIZER_PROMPT + instruction),
                new ChatMessage("user", render(body))), List.of());
        if (StrUtil.isBlank(answer.content()) || !answer.toolCalls().isEmpty()) {
            throw new IOException("总结响应没有文字");
        }
        String summary = answer.content().strip();
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS) + "\n…（摘要已截断）";
        }
        StringBuilder text = new StringBuilder(SUMMARY_MARKER)
                .append(" 之前的对话已被总结，完整记录保存在工作目录的 ").append(relative(file))
                .append("（JSONL），需要原文细节时可用 read_file 读取。\n\n摘要：\n").append(summary);
        String current = state == null ? null : state.get();
        if (StrUtil.isNotBlank(current)) {
            text.append("\n\n程序保存的当前状态（不是摘要，以此为准）：\n").append(current);
        }
        if (latestInput != null) {
            text.append("\n\n用户最新输入（原文）：\n").append(latestInput);
        }
        List<ChatMessage> result = new ArrayList<>();
        if (hasSystem) {
            result.add(micro.get(0));
        }
        result.add(new ChatMessage("user", text.toString()));
        return result;
    }

    private static int conversationSize(List<ChatMessage> messages) {
        boolean hasSystem = !messages.isEmpty() && "system".equals(messages.get(0).role());
        return MicroCompact.size(hasSystem ? messages.subList(1, messages.size()) : messages);
    }

    /** 最近一条用户消息若是之前的摘要则不重复附加（它已交给模型总结）。 */
    private static String latestUserInput(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if ("user".equals(message.role())) {
                return message.content() == null || message.content().startsWith(SUMMARY_MARKER)
                        ? null : message.content();
            }
        }
        return null;
    }

    /** 除系统提示词和本轮新输入外的消息数。 */
    private static int summarizable(List<ChatMessage> messages) {
        int count = messages.size();
        if (count > 0 && "system".equals(messages.get(0).role())) {
            count--;
        }
        if (count > 0 && "user".equals(messages.get(messages.size() - 1).role())) {
            count--;
        }
        return count;
    }

    static String render(List<ChatMessage> messages) {
        StringBuilder text = new StringBuilder();
        for (ChatMessage message : messages) {
            if (StrUtil.isNotBlank(message.content())) {
                text.append('[').append(message.role()).append("] ").append(message.content().strip()).append('\n');
            }
            for (ToolCall call : message.toolCalls()) {
                text.append("[assistant 调用 ").append(call.name()).append("] ").append(call.arguments()).append('\n');
            }
        }
        if (text.length() <= MAX_SUMMARY_INPUT_CHARS) {
            return text.toString();
        }
        // 过长时保留最近的部分，较早内容仍在完整记录中。
        return "…（较早的记录已省略）\n" + text.substring(text.length() - MAX_SUMMARY_INPUT_CHARS);
    }

    private String relative(Path file) {
        Path base = workDirectory.toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        return (target.startsWith(base) ? base.relativize(target) : target).toString().replace('\\', '/');
    }

    private static void print(PrintWriter output, String line) {
        output.println(line);
        output.flush();
    }
}
