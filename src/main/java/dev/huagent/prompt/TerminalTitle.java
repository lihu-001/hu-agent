package dev.huagent.prompt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * hu 交互会话的终端标题（Windows Terminal 的标签页、Linux / macOS 终端的窗口或标签标题），随会话状态变化：
 * 等待输入 "✅ hu 就绪 · 目录名"，模型与工具运行中 "⏳ hu 运行中 · 目录名"，
 * 运行中等用户回答（权限确认等）"🔔 hu 待确认 · 目录名"。目录名区分同时开着的多个会话。
 *
 * <p>包装会话的提示符样式与终端输入：画出输入提示符时为就绪，读到一行输入后为运行中；
 * 运行中又开始读取一行时是确认问答，为待确认，读完回到运行中。
 * 标题由 LineEditor 写出（见 LineEditor.title），没有行编辑器（输入输出被重定向）时 HuMain 不设置标题。
 */
public final class TerminalTitle {
    enum Status {
        READY("✅", "就绪"), WORKING("⏳", "运行中"), ASKING("🔔", "待确认");

        private final String icon;
        private final String label;

        Status(String icon, String label) {
            this.icon = icon;
            this.label = label;
        }
    }

    private final Consumer<String> terminal;
    private final String directory;
    /** 主线程（提示符）与读终端的守护线程（InputPump）都会改状态，方法都在本对象的锁下。 */
    private Status status;

    public TerminalTitle(Consumer<String> terminal, Path directory) {
        this.terminal = Objects.requireNonNull(terminal);
        Path absolute = directory.toAbsolutePath().normalize();
        // 根目录没有文件名，显示整个路径。
        this.directory = absolute.getFileName() == null ? absolute.toString() : absolute.getFileName().toString();
    }

    /** 包装会话的提示符样式：begin 之后为就绪；end 读到非空的一行（含程序事件的哨兵行）之后为运行中。 */
    public PromptStyle prompt(PromptStyle style) {
        Objects.requireNonNull(style);
        return new PromptStyle() {
            @Override
            public void begin(PrintWriter output) {
                style.begin(output);
                show(Status.READY);
            }

            @Override
            public void end(PrintWriter output, String line) {
                style.end(output, line);
                // 空行马上回到提示符，不切换，避免标题闪一下；输入结束时由 LineEditor 关闭时恢复原标题。
                if (line != null && !line.isBlank()) {
                    show(Status.WORKING);
                }
            }

            @Override
            public void context(int usedTokens) {
                style.context(usedTokens);
            }
        };
    }

    /** 包装终端输入：运行中开始读取的一行是确认问答，读取期间为待确认。 */
    public BufferedReader input(BufferedReader input) {
        Objects.requireNonNull(input);
        return new BufferedReader(Reader.nullReader()) {
            @Override
            public String readLine() throws IOException {
                change(Status.WORKING, Status.ASKING);
                try {
                    return input.readLine();
                } finally {
                    change(Status.ASKING, Status.WORKING);
                }
            }
        };
    }

    String text(Status status) {
        return status.icon + " hu " + status.label + " · " + directory;
    }

    private synchronized void show(Status next) {
        if (next != status) {
            status = next;
            terminal.accept(text(next));
        }
    }

    private synchronized void change(Status from, Status to) {
        if (status == from) {
            show(to);
        }
    }
}
