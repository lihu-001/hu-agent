package dev.huagent.team;

import dev.huagent.hook.AgentHook;
import dev.huagent.task.TaskStore;
import dev.huagent.task.TaskTools;
import dev.huagent.tool.ToolCall;

import cn.hutool.core.util.StrUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队管理：Lead 在主线程上通过它创建队友、发消息、请求关闭；每个队友是独立线程上的完整 Agent。
 * 通信只走收件箱（MessageBus），协调状态在 .tasks 中；成员表只记录本进程中活着的队友线程。
 */
public final class TeamManager {
    public static final String LEAD = "lead";
    /** 保留名：lead 是主 Agent 的收件箱，agent 是 S10 起 Lead 认领任务时使用的 owner。 */
    public static final Set<String> RESERVED = Set.of(LEAD, TaskTools.DEFAULT_OWNER);
    public static final int MAX_TEAMMATES = 4;
    public static final int MAX_ROLE_CHARS = 200;
    public static final int MAX_PROMPT_CHARS = 8000;
    public static final Duration DEFAULT_IDLE_SCAN = Duration.ofSeconds(30);
    /** 会话结束时等待每个队友线程退出的上限。 */
    public static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MessageBus bus;
    private final TaskStore tasks;
    private final TeammateSetup.Factory factory;
    private final PrintWriter output;
    private final Duration idleScan;
    private final WorktreeManager worktrees;
    private final Map<String, Teammate> members = new ConcurrentHashMap<>();
    /** 已发出、尚未收到响应的关闭请求：requestId → 队友名。 */
    private final Map<String, String> pendingShutdowns = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public TeamManager(MessageBus bus, TaskStore tasks, TeammateSetup.Factory factory, PrintWriter output) {
        this(bus, tasks, factory, output, DEFAULT_IDLE_SCAN);
    }

    public TeamManager(MessageBus bus, TaskStore tasks, TeammateSetup.Factory factory, PrintWriter output,
                       Duration idleScan) {
        this(bus, tasks, factory, output, idleScan, null);
    }

    /** worktrees 为 null 时不支持 worktree，要求 worktree 的 spawn 直接报错。 */
    public TeamManager(MessageBus bus, TaskStore tasks, TeammateSetup.Factory factory, PrintWriter output,
                       Duration idleScan, WorktreeManager worktrees) {
        this.worktrees = worktrees;
        this.bus = Objects.requireNonNull(bus);
        this.tasks = Objects.requireNonNull(tasks);
        this.factory = Objects.requireNonNull(factory);
        this.output = Objects.requireNonNull(output);
        if (idleScan == null || idleScan.isZero() || idleScan.isNegative()) {
            throw new IllegalArgumentException("空闲扫描间隔必须为正数");
        }
        this.idleScan = idleScan;
    }

    public MessageBus bus() {
        return bus;
    }

    public Teammate spawn(String name, String role, String prompt, String taskId) throws IOException {
        return spawn(name, role, prompt, taskId, false);
    }

    /**
     * 创建并启动一个队友。顺序：校验 → 准备 worktree → 组装部件 → 认领初始任务 → 启动线程；
     * 任何一步失败都不会留下线程或认领。worktree 一旦建好就保留（之后同名队友复用），不随失败删除。
     * 认领在线程启动前完成，队友第一轮就知道自己的任务。
     */
    public synchronized Teammate spawn(String name, String role, String prompt, String taskId, boolean worktree)
            throws IOException {
        if (closed) {
            throw new IllegalStateException("团队已关闭。");
        }
        MessageBus.validateName(name);
        if (RESERVED.contains(name)) {
            throw new IllegalArgumentException(name + " 是保留名，请换一个队友名");
        }
        if (members.containsKey(name)) {
            throw new IllegalArgumentException("已有名为 " + name + " 的队友");
        }
        if (members.size() >= MAX_TEAMMATES) {
            throw new IllegalArgumentException("同时最多 " + MAX_TEAMMATES + " 个队友，请先关闭不再需要的队友");
        }
        String cleanRole = requireText(role, "role", MAX_ROLE_CHARS);
        String cleanPrompt = requireText(prompt, "prompt", MAX_PROMPT_CHARS);
        if (taskId != null) {
            TaskStore.validateId(taskId);
        }
        WorktreeManager.Worktree workspace = null;
        if (worktree) {
            if (worktrees == null) {
                throw new IllegalArgumentException("本会话不支持 worktree");
            }
            try {
                workspace = worktrees.prepare(name);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("创建 worktree 时被中断", e);
            }
        }
        PrintWriter memberOutput = TeammateSetup.prefixed(output, name);
        TeammateSetup setup = factory.create(name, cleanRole, workspace, this, memberOutput);
        String initial = cleanPrompt;
        if (taskId != null) {
            var task = tasks.claim(taskId, name, true);
            initial = "[已分配任务 " + task.id() + "] " + task.subject()
                    + (task.description().isEmpty() ? "" : "\n" + task.description())
                    + "\n完成后用 task_complete 标记。\n\n" + cleanPrompt;
        }
        Teammate teammate = new Teammate(name, cleanRole, initial, this, bus, tasks, setup, workspace, idleScan);
        members.put(name, teammate);
        teammate.start();
        return teammate;
    }

    /** 自由消息：任何成员都能发给 lead 或其他活着的队友，不经过 Lead 转发。 */
    public void send(String from, String to, String content) throws IOException {
        MessageBus.validateName(to);
        if (from.equals(to)) {
            throw new IllegalArgumentException("不能给自己发消息");
        }
        if (!LEAD.equals(to) && !members.containsKey(to)) {
            throw new IllegalArgumentException("没有名为 " + to + " 的活跃队友；可用 list_teammates 查看");
        }
        bus.send(new TeamMessage(from, to, TeamMessage.MESSAGE, requireText(content, "content",
                MessageBus.MAX_CONTENT_CHARS)));
    }

    /** 发送关闭请求；队友做完当前这一轮后回复并退出，响应以 requestId 配对。 */
    public String requestShutdown(String name) throws IOException {
        MessageBus.validateName(name);
        if (!members.containsKey(name)) {
            throw new IllegalArgumentException("没有名为 " + name + " 的活跃队友");
        }
        byte[] random = new byte[3];
        RANDOM.nextBytes(random);
        String requestId = "req_" + HexFormat.of().formatHex(random);
        pendingShutdowns.put(requestId, name);
        bus.send(new TeamMessage(LEAD, name, TeamMessage.SHUTDOWN_REQUEST, "请完成当前这一轮后退出。", requestId, null));
        return requestId;
    }

    /** 一行一个活跃队友：名称、角色、状态和进行中的任务。 */
    public String render() throws IOException {
        if (members.isEmpty()) {
            return "当前没有队友。用 spawn_teammate 创建。";
        }
        List<String> lines = new ArrayList<>();
        for (Teammate teammate : members.values().stream()
                .sorted((a, b) -> a.name().compareTo(b.name())).toList()) {
            List<String> holding = tasks.inProgress(teammate.name());
            lines.add(teammate.name() + "（" + teammate.role() + "） " + teammate.status().wireName()
                    + (teammate.worktree() == null ? "" : " 分支=" + teammate.worktree().branch())
                    + (holding.isEmpty() ? "" : " 任务=" + String.join(",", holding)));
        }
        return String.join("\n", lines);
    }

    public List<String> names() {
        return members.keySet().stream().sorted().toList();
    }

    public Teammate member(String name) {
        return members.get(name);
    }

    /**
     * Lead 的收件箱：取出全部消息并渲染为 &lt;team-events&gt;，没有消息时返回 null。
     * 关闭响应必须与已发出的请求配对（ID、发送者都对得上），否则标注为忽略。
     */
    public String leadMail() {
        List<TeamMessage> mail;
        try {
            mail = bus.drain(LEAD);
        } catch (IOException e) {
            return null;
        }
        List<TeamMessage> checked = new ArrayList<>();
        for (TeamMessage message : mail) {
            if (TeamMessage.SHUTDOWN_RESPONSE.equals(message.type())) {
                String target = message.requestId() == null ? null : pendingShutdowns.get(message.requestId());
                if (message.from().equals(target)) {
                    pendingShutdowns.remove(message.requestId());
                } else {
                    message = new TeamMessage(message.from(), message.to(), message.type(),
                            "（没有对应的关闭请求，已忽略）", message.requestId(), message.sentAt());
                }
            }
            checked.add(message);
        }
        return render(checked);
    }

    /** 任务图变化后唤醒空闲队友去认领；作为 PostToolUse Hook 挂在 Lead 与队友的工具链上。 */
    public AgentHook taskWakeHook() {
        return new AgentHook() {
            @Override
            public void postToolUse(ToolCall call, String result, long elapsedNanos) {
                if (Set.of(TaskTools.CREATE, TaskTools.UPDATE, TaskTools.COMPLETE).contains(call.name())) {
                    bus.wakeAll();
                }
            }

            @Override
            public String describe() {
                return "团队 PostToolUse：任务图变化时唤醒空闲队友";
            }
        };
    }

    /** 会话结束：停止所有队友线程并等待退出；它们手上的任务在各自线程的 finally 中退回 pending。 */
    public int shutdownAll() throws InterruptedException {
        closed = true;
        List<Teammate> running = List.copyOf(members.values());
        for (Teammate teammate : running) {
            teammate.stop();
        }
        for (Teammate teammate : running) {
            if (!teammate.join(STOP_TIMEOUT)) {
                synchronized (output) {
                    output.println("团队> " + teammate.name() + " 未在 " + STOP_TIMEOUT.toSeconds() + " 秒内退出，已放弃等待。");
                    output.flush();
                }
            }
        }
        return running.size();
    }

    void removed(Teammate teammate) {
        members.remove(teammate.name(), teammate);
        pendingShutdowns.values().removeIf(teammate.name()::equals);
    }

    /** 消息列表渲染为一个 &lt;team-events&gt; 块，作为下一轮的用户输入；空列表返回 null。 */
    static String render(List<TeamMessage> messages) {
        if (messages.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder("<team-events>");
        for (TeamMessage message : messages) {
            text.append("\n[").append(message.type());
            if (message.requestId() != null) {
                text.append(' ').append(message.requestId());
            }
            text.append("] ").append(message.from()).append("：").append(message.content());
        }
        return text.append("\n</team-events>").toString();
    }

    static String truncate(String text) {
        return text.length() <= MessageBus.MAX_CONTENT_CHARS ? text
                : text.substring(0, MessageBus.MAX_CONTENT_CHARS) + "…（已截断）";
    }

    private static String requireText(String value, String field, int max) {
        if (StrUtil.isBlank(value) || value.strip().length() > max) {
            throw new IllegalArgumentException(field + " 必须是非空文字，最多 " + max + " 字符");
        }
        return value.strip();
    }
}
