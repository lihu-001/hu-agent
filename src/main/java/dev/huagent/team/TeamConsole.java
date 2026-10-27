package dev.huagent.team;

import dev.huagent.harness.EventHub;
import dev.huagent.harness.TurnEvents;
import dev.huagent.prompt.PromptStyle;
import dev.huagent.tool.ToolCall;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

/**
 * 主线程的事件队列：终端输入、Lead 收件箱的唤醒、队友的权限确认请求都放进同一个队列，只有主线程取出处理。
 * System.in 的阻塞读取不能被唤醒，所以由一个守护线程只负责把读到的行搬进队列，不做任何决定。
 * 两个读取视图：prompts 给 Lead 的 ChatLoop（会被队友消息唤醒），answers 给确认提示（只要用户的下一行）。
 */
public final class TeamConsole implements AutoCloseable {
    private sealed interface Event permits Line, End, Wake, Confirm {
    }

    private record Line(String text) implements Event {
    }

    private record End() implements Event {
    }

    private record Wake() implements Event {
    }

    private record Confirm(String member, String reason, ToolCall call, CompletableFuture<Boolean> answer)
            implements Event {
    }

    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final PrintWriter output;
    /** 正在等待用户回答时到达的确认请求，回到提示符时依次询问；只由主线程访问。 */
    private final Deque<Confirm> waiting = new ArrayDeque<>();
    private final Set<CompletableFuture<Boolean>> open = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private boolean ended;
    /** 确认问答之后重画 Lead 的提示符，与 ChatLoop 使用同一样式。 */
    private final PromptStyle prompt;

    public TeamConsole(BufferedReader terminal, PrintWriter output) {
        this(terminal, output, PromptStyle.plain());
    }

    public TeamConsole(BufferedReader terminal, PrintWriter output, PromptStyle prompt) {
        Objects.requireNonNull(terminal);
        this.output = Objects.requireNonNull(output);
        this.prompt = Objects.requireNonNull(prompt);
        Thread pump = new Thread(() -> {
            try {
                String line;
                while ((line = terminal.readLine()) != null) {
                    events.add(new Line(line));
                }
            } catch (IOException e) {
                // 终端不可读按输入结束处理。
            }
            events.add(new End());
        }, "terminal-input");
        pump.setDaemon(true);
        pump.start();
    }

    /** Lead 的输入：用户的一行，或者没有输入时的队友消息（mail 返回 null 表示没有）。 */
    public BufferedReader prompts(Supplier<String> mail) {
        return prompts(mail, null);
    }

    /** S15：hub 非 null 时，程序事件到达也会唤醒空闲的 Lead（以哨兵行返回，事件由 ChatLoop 送达）。 */
    public BufferedReader prompts(Supplier<String> mail, EventHub hub) {
        return new LineReader() {
            @Override
            String next() throws InterruptedException {
                return nextPrompt(mail, hub);
            }
        };
    }

    /** 确认提示的输入：只返回用户的下一行；期间到达的队友消息留在收件箱，确认请求排队。 */
    public BufferedReader answers() {
        return new LineReader() {
            @Override
            String next() throws InterruptedException {
                return nextAnswer();
            }
        };
    }

    /** 队友线程调用：排队等主线程询问用户，阻塞到有回答；控制台关闭或输入结束时视为拒绝。 */
    public boolean confirm(String member, String reason, ToolCall call) throws InterruptedException {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        open.add(answer);
        try {
            if (closed) {
                return false;
            }
            events.add(new Confirm(member, reason, call, answer));
            return answer.get();
        } catch (ExecutionException e) {
            return false;
        } finally {
            // 被中断时作废请求，主线程取到后跳过，不再询问用户。
            answer.complete(false);
            open.remove(answer);
        }
    }

    /** 收件箱有新消息时调用（任意线程）；只是提示，消息本身仍在文件里。 */
    public void wake() {
        events.add(new Wake());
    }

    /** 拒绝所有等待中的确认，之后的确认请求直接拒绝。 */
    @Override
    public void close() {
        closed = true;
        for (CompletableFuture<Boolean> answer : open) {
            answer.complete(false);
        }
    }

    private String nextPrompt(Supplier<String> mail, EventHub hub) throws InterruptedException {
        while (!ended) {
            Confirm pending = waiting.poll();
            if (pending != null) {
                serve(pending);
                reprompt();
                continue;
            }
            String events = mail.get();
            if (events != null) {
                say("团队> 收到队友消息，交给 Lead 处理。");
                return events;
            }
            Event event = this.events.take();
            if (event instanceof Line line) {
                // exit、quit 和空行原样交给 ChatLoop 判断，不附加消息。
                String text = line.text().strip();
                if (text.isEmpty() || text.equalsIgnoreCase("exit") || text.equalsIgnoreCase("quit")) {
                    return line.text();
                }
                String extra = mail.get();
                return extra == null ? line.text() : line.text() + "\n\n" + extra;
            }
            if (event instanceof End) {
                ended = true;
            } else if (event instanceof Confirm confirm) {
                serve(confirm);
                reprompt();
            } else if (event instanceof Wake) {
                // S15：程序事件唤醒——队友消息优先；没有消息而事件中心有待送达内容时交哨兵行，
                // 事件本身由 ChatLoop 在下一次请求前取走。
                if (hub != null && hub.hasPending()) {
                    return TurnEvents.WAKE_LINE;
                }
            }
        }
        return null;
    }

    private String nextAnswer() throws InterruptedException {
        while (!ended) {
            Event event = events.take();
            if (event instanceof Line line) {
                return line.text();
            }
            if (event instanceof End) {
                ended = true;
            } else if (event instanceof Confirm confirm) {
                waiting.add(confirm);
            }
        }
        return null;
    }

    private void serve(Confirm confirm) throws InterruptedException {
        if (confirm.answer().isDone()) {
            return;
        }
        say("权限确认> [" + confirm.member() + "] " + confirm.reason());
        say("工具> " + confirm.call().name() + " [" + confirm.call().id() + "]");
        say("参数> " + confirm.call().arguments());
        synchronized (output) {
            output.print("允许队友 " + confirm.member() + " 本次调用？[y/N] ");
            output.flush();
        }
        String answer = nextAnswer();
        String choice = answer == null ? "" : answer.strip();
        boolean approved = choice.equalsIgnoreCase("y") || choice.equalsIgnoreCase("yes");
        if (!confirm.answer().complete(approved)) {
            say("权限确认> [" + confirm.member() + "] 请求已失效（队友已停止），本次回答未使用。");
        }
    }

    /** 确认问答打断了 ChatLoop 打印的提示符，补一个，让用户知道又轮到 Lead 的输入了。 */
    private void reprompt() {
        synchronized (output) {
            prompt.begin(output);
            output.flush();
        }
    }

    private void say(String line) {
        synchronized (output) {
            output.println(line);
            output.flush();
        }
    }

    /** ChatLoop、PermissionGate 和 HookTrust 只调用 readLine。 */
    private abstract static class LineReader extends BufferedReader {
        LineReader() {
            super(Reader.nullReader());
        }

        abstract String next() throws InterruptedException;

        @Override
        public String readLine() {
            try {
                return next();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }
}
