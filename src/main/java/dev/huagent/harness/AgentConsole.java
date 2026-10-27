package dev.huagent.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * S15：单 Agent 模式的主线程事件队列，机制与 TeamConsole 相同：
 * System.in 的阻塞读取不能被唤醒，由守护线程把读到的行搬进队列；程序事件到达时唤醒空闲的循环。
 * prompts 给 ChatLoop（会被程序事件以哨兵行唤醒），answers 给确认提示（只返回用户的下一行）。
 */
public final class AgentConsole implements AutoCloseable {
    private sealed interface Event permits Line, End, Wake {
    }

    private record Line(String text) implements Event {
    }

    private record End() implements Event {
    }

    private record Wake() implements Event {
    }

    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final PrintWriter output;
    private volatile boolean closed;
    private boolean ended;

    public AgentConsole(BufferedReader terminal, PrintWriter output) {
        Objects.requireNonNull(terminal);
        this.output = Objects.requireNonNull(output);
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

    /** 程序事件到达时调用（任意线程）；正在等待输入的循环会被哨兵行唤醒。 */
    public void notifyEvents() {
        if (!closed) {
            events.add(new Wake());
        }
    }

    /** ChatLoop 的输入：用户的一行，或没有输入时的程序事件哨兵行。 */
    public BufferedReader prompts() {
        return new LineReader() {
            @Override
            String next() throws InterruptedException {
                return nextPrompt();
            }
        };
    }

    /** 确认提示的输入：只返回用户的下一行，程序事件不打断确认。 */
    public BufferedReader answers() {
        return new LineReader() {
            @Override
            String next() throws InterruptedException {
                return nextAnswer();
            }
        };
    }

    private String nextPrompt() throws InterruptedException {
        while (!ended) {
            Event event = events.take();
            if (event instanceof Line line) {
                return line.text();
            }
            if (event instanceof End) {
                ended = true;
            } else if (event instanceof Wake) {
                return TurnEvents.WAKE_LINE;
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
            }
            // Wake：确认中不唤醒，事件留在队列里，等回到提示符后再处理。
        }
        return null;
    }

    /** 拒绝之后的唤醒请求；已排队的事件随输入结束一并失效。 */
    @Override
    public void close() {
        closed = true;
    }

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
