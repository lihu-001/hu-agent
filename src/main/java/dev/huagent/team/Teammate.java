package dev.huagent.team;

import dev.huagent.task.TaskRecord;
import dev.huagent.model.ChatMessage;
import dev.huagent.task.TaskStore;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 一个常驻队友：独立线程上的 WORK ⇄ IDLE 循环。
 * WORK：处理收件箱（关闭请求优先），跑一轮 Agent，把最终回复作为 result 发给 Lead。
 * IDLE：没有输入时先尝试认领一个可开始的任务，否则在收件箱上阻塞等待，被消息或任务变化唤醒。
 * 线程退出时把手上未完成的任务退回 pending。
 */
public final class Teammate implements Runnable {
    public enum Status {
        WORKING, IDLE, STOPPED;

        public String wireName() {
            return name().toLowerCase();
        }
    }

    private final String name;
    private final String role;
    private final TeamManager team;
    private final MessageBus bus;
    private final TaskStore tasks;
    private final TeammateAgent agent;
    private final PrintWriter output;
    private final Duration idleScan;
    private final WorktreeManager.Worktree worktree;
    private String input;
    private volatile Status status = Status.IDLE;
    private volatile boolean stopping;
    private volatile Thread thread;

    Teammate(String name, String role, String initialInput, TeamManager team, MessageBus bus, TaskStore tasks,
             TeammateSetup setup, WorktreeManager.Worktree worktree, Duration idleScan) {
        this.worktree = worktree;
        this.name = Objects.requireNonNull(name);
        this.role = Objects.requireNonNull(role);
        this.input = Objects.requireNonNull(initialInput);
        this.team = Objects.requireNonNull(team);
        this.bus = Objects.requireNonNull(bus);
        this.tasks = Objects.requireNonNull(tasks);
        this.agent = new TeammateAgent(setup);
        this.output = setup.output();
        this.idleScan = Objects.requireNonNull(idleScan);
    }

    public String name() {
        return name;
    }

    public String role() {
        return role;
    }

    public Status status() {
        return status;
    }

    /** 队友的 worktree；与 Lead 共用工作目录时为 null。 */
    public WorktreeManager.Worktree worktree() {
        return worktree;
    }

    void start() {
        Thread worker = new Thread(this, "teammate-" + name);
        worker.setDaemon(true);
        thread = worker;
        worker.start();
    }

    /** 会话结束：不再发消息给 Lead，中断等待中的模型请求、命令或确认。 */
    void stop() {
        stopping = true;
        Thread worker = thread;
        if (worker != null) {
            worker.interrupt();
        }
    }

    boolean join(Duration timeout) throws InterruptedException {
        Thread worker = thread;
        if (worker == null) {
            return true;
        }
        worker.join(timeout.toMillis());
        return !worker.isAlive();
    }

    @Override
    public void run() {
        String exit = null;
        boolean requested = false;
        String answered = null;
        try {
            while (!stopping && !Thread.currentThread().isInterrupted()) {
                List<TeamMessage> mail = bus.drain(name);
                TeamMessage shutdown = mail.stream()
                        .filter(m -> TeamMessage.SHUTDOWN_REQUEST.equals(m.type()) && TeamManager.LEAD.equals(m.from()))
                        .findFirst().orElse(null);
                if (shutdown != null) {
                    // 关闭请求在一轮结束后才处理：正在进行的模型请求和工具调用会先做完。
                    requested = true;
                    answered = shutdown.requestId();
                    say("收到关闭请求 " + shutdown.requestId() + "，退出。");
                    bus.send(new TeamMessage(name, TeamManager.LEAD, TeamMessage.SHUTDOWN_RESPONSE,
                            "已停止。", shutdown.requestId(), null));
                    return;
                }
                String events = TeamManager.render(mail);
                if (events != null) {
                    input = input == null ? events : input + "\n\n" + events;
                }
                if (input == null) {
                    TaskRecord claimed = tasks.claimNext(name);
                    if (claimed != null) {
                        say("空闲时自动认领 " + claimed.id() + "：" + claimed.subject());
                        input = "[自动认领任务 " + claimed.id() + "] " + claimed.subject()
                                + (claimed.description().isEmpty() ? "" : "\n" + claimed.description())
                                + "\n完成后用 task_complete 标记。";
                    }
                }
                if (input == null) {
                    status = Status.IDLE;
                    bus.await(name, idleScan);
                    continue;
                }
                status = Status.WORKING;
                String current = input;
                input = null;
                TeammateAgent.Outcome outcome = agent.turn(current);
                status = Status.IDLE;
                if (!stopping) {
                    // 有 worktree 时附上分支名，Lead 据此合并。
                    String text = worktree == null ? outcome.text()
                            : outcome.text() + "\n（工作分支 " + worktree.branch() + "）";
                    bus.send(new TeamMessage(name, TeamManager.LEAD,
                            outcome.completed() ? TeamMessage.RESULT : TeamMessage.ERROR,
                            TeamManager.truncate(text)));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = "线程被中断";
        } catch (IOException | RuntimeException e) {
            exit = "运行异常（" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()) + "）";
        } finally {
            status = Status.STOPPED;
            finish(exit, requested, answered);
        }
    }

    /** 当前历史的快照，供测试检查队友的上下文。 */
    public List<ChatMessage> history() {
        return agent.history();
    }

    private void finish(String exit, boolean requested, String answered) {
        // 退回任务要获取 FileChannel 文件锁，而 FileChannel 遇到中断标记会直接关闭；先清除标记，结束时再恢复。
        boolean interrupted = Thread.interrupted();
        try {
            List<TaskRecord> released = tasks.release(name);
            for (TaskRecord task : released) {
                say("退出时退回未完成的任务 " + task.id() + "：" + task.subject());
            }
        } catch (IOException | RuntimeException e) {
            say("退出时无法退回任务（" + e.getClass().getSimpleName() + "）。");
        }
        team.removed(this, answered);
        if (!requested && !stopping) {
            try {
                bus.send(new TeamMessage(name, TeamManager.LEAD, TeamMessage.STOPPED,
                        "队友意外退出：" + (exit == null ? "未知原因" : exit) + "。"));
            } catch (IOException | RuntimeException ignored) {
                // 收件箱不可写时只能留下终端日志。
            }
        }
        if (exit != null && !stopping) {
            say("已退出：" + exit);
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void say(String line) {
        output.println(line);
        output.flush();
    }
}
