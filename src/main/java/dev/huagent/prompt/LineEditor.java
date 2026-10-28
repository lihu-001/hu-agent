package dev.huagent.prompt;

import dev.huagent.command.SlashCommand;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;

import java.io.BufferedReader;
import java.io.IOError;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * hu 交互会话的行编辑器（JLine）：方向键编辑与历史，斜杠命令补全。行首输入 / 时在输入行下方列出命令（SlashMenu），
 * 继续输入即时过滤；最多显示 10 个，↑↓ 选择、列表跟着滚动，鼠标滚轮也能滚动列表。
 * Tab 把选中的命令（未选中时为第一个）填进输入行，回车在选中后填入，再回车执行。
 *
 * <p>输出与读取的配合：ChatLoop 和各种确认先打印提示语（"❯ "、"[y/N] "），再读一行。output 留住还没换行的部分，
 * 读取开始时交给 JLine 当作提示符画出；读取期间其他线程（后台任务、定时计划、队友）的整行输出经 printAbove
 * 插到输入行上方，不打乱正在输入的内容。读取只在有人等待输入时进行（InputPump），模型回复期间不显示提示符。
 *
 * <p>取不到系统终端时 open 返回 null，调用方继续用 Console 按行读取。
 */
public final class LineEditor implements AutoCloseable {
    /** 读取期间经 callWidget 写出控制序列：拿着 JLine 的锁，不会插进输入行重画的转义序列中间。 */
    private static final String CONTROL_WIDGET = "hu-control";
    /** 第一次设置标题前把原标题压栈（XTPUSHTITLE）。 */
    private static final String SAVE_TITLE = "\u001b[22;0t";
    /** 先清空标题，不支持标题栈的终端由此回到默认标题；支持的终端随后出栈恢复原标题（XTPOPTITLE）。 */
    private static final String RESTORE_TITLE = "\u001b]0;\u0007\u001b[23;0t";

    private final Terminal terminal;
    private final MenuReader reader;
    private final SlashMenu menu;
    private final PromptWriter writer = new PromptWriter();
    private final PrintWriter output = new PrintWriter(writer, true);

    LineEditor(Terminal terminal, Supplier<? extends Collection<SlashCommand>> commands) {
        this.terminal = Objects.requireNonNull(terminal);
        this.menu = new SlashMenu(new SlashCompleter(commands));
        this.reader = new MenuReader(terminal, menu);
        // 原样返回输入：不展开 !! 之类的历史引用，也不把反斜杠当转义去掉（Windows 路径）。
        reader.option(LineReader.Option.DISABLE_EVENT_EXPANSION, true);
        reader.setVariable(LineReader.BELL_STYLE, "none");
        // 方向键绑定的是 *-line-or-search；Ctrl+N / Ctrl+P 的 *-line-or-history 保持只翻历史。
        onMenu(LineReader.DOWN_LINE_OR_SEARCH, () -> {
            menu.move(1);
            return true;
        });
        onMenu(LineReader.UP_LINE_OR_SEARCH, () -> {
            menu.move(-1);
            return true;
        });
        onMenu(LineReader.EXPAND_OR_COMPLETE, () -> fill(menu.choice(true)));
        onMenu(LineReader.ACCEPT_LINE, () -> fill(menu.choice(false)));
        reader.getWidgets().put(LineReader.MOUSE, this::mouse);
        reader.getWidgets().put(CONTROL_WIDGET, () -> {
            terminal.writer().write(writer.control);
            terminal.flush();
            return true;
        });
    }

    /** 打开系统终端；不是交互终端或平台不支持时返回 null。 */
    public static LineEditor open(Supplier<? extends Collection<SlashCommand>> commands) {
        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().system(true).dumb(false).build();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (terminal.getType().startsWith(Terminal.TYPE_DUMB)) {
            closeQuietly(terminal);
            return null;
        }
        return new LineEditor(terminal, commands);
    }

    /** 会话的全部输出都经过它，见类注释。 */
    public PrintWriter output() {
        return output;
    }

    /** 每次 readLine 读一行，以 output 中尚未换行的提示语作为提示符；输入结束（Ctrl+D、空行上 Ctrl+C）时返回 null。 */
    public BufferedReader input() {
        return new BufferedReader(Reader.nullReader()) {
            @Override
            public String readLine() throws IOException {
                String prompt = writer.beginRead();
                try {
                    while (true) {
                        try {
                            // JLine 把提示符里的 % 当作格式（%N、%{…%} 等，未知的直接丢掉），
                            // 确认提示里有模型给出的命令，必须原样显示。
                            return reader.readLine(prompt.replace("%", "%%"));
                        } catch (UserInterruptException e) {
                            // Ctrl+C：清掉已输入的内容重新读；空行上按下时与 Ctrl+D 一样结束输入。
                            if (e.getPartialLine() == null || e.getPartialLine().isEmpty()) {
                                return null;
                            }
                        }
                    }
                } catch (EndOfFileException e) {
                    return null;
                } catch (IOError e) {
                    throw new IOException(e);
                } finally {
                    writer.endRead();
                }
            }
        };
    }

    /** 终端当前列数，窗口缩放后跟着变；取不到时为 TerminalWidth.DEFAULT。 */
    public int columns() {
        int width = terminal.getWidth();
        return width > 0 ? width : TerminalWidth.DEFAULT;
    }

    /**
     * 设置终端标题（OSC 0，Windows Terminal 与常见 Linux / macOS 终端都支持；Windows 传统控制台由 JLine 转成 SetConsoleTitle）。
     * 不占屏幕位置，读取期间也可以调用；控制字符替换为空格。第一次设置前保存原标题，close 时恢复。
     */
    public void title(String text) {
        writer.title(text.replaceAll("\\p{Cntrl}", " "));
    }

    /** 恢复终端标题与终端模式；之后的输出丢弃（只剩退出时的守护线程）。 */
    @Override
    public void close() {
        writer.close();
        closeQuietly(terminal);
    }

    /** 命令列表显示时按键先交给 action；列表不显示或 action 不处理（返回 false）时照常执行原来的功能。 */
    private void onMenu(String widget, BooleanSupplier action) {
        Widget original = reader.getWidgets().get(widget);
        reader.getWidgets().put(widget, () -> reader.showing() && action.getAsBoolean() || original.apply());
    }

    /** 用候选替换整行输入，按候选的要求补一个空格；candidate 为 null 时不处理，返回 false。 */
    private boolean fill(Candidate candidate) {
        if (candidate == null) {
            return false;
        }
        reader.getBuffer().clear();
        reader.getBuffer().write(candidate.complete() ? candidate.value() + " " : candidate.value());
        return true;
    }

    /** 鼠标跟踪只在列表显示时开启（见 MenuReader）：滚轮滚动列表，其他鼠标事件忽略。 */
    private boolean mouse() {
        MouseEvent event = reader.readMouseEvent();
        if (event.getType() == MouseEvent.Type.Wheel && reader.showing()) {
            menu.scroll(event.getButton() == MouseEvent.Button.WheelDown ? 1 : -1);
        }
        return true;
    }

    private static void closeQuietly(Terminal terminal) {
        try {
            terminal.close();
        } catch (IOException e) {
            // 退出时恢复终端模式失败没有补救办法。
        }
    }

    /** 见类注释。PrintWriter 以本对象为锁，beginRead 与 endRead 也在同一把锁下。 */
    private final class PromptWriter extends Writer {
        /** 已写入、尚未输出的文字：读取期间不足一行的部分，或者等着当提示符的提示语。 */
        private final StringBuilder pending = new StringBuilder();
        private boolean reading;
        private String prompt = "";
        private boolean closed;
        private boolean titled;
        /** 交给 CONTROL_WIDGET 写出的控制序列。 */
        private String control = "";

        @Override
        public void write(char[] buffer, int offset, int length) {
            synchronized (lock) {
                if (!closed) {
                    pending.append(buffer, offset, length);
                }
            }
        }

        @Override
        public void flush() {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                int end = pending.lastIndexOf("\n") + 1;
                if (end > 0) {
                    String lines = pending.substring(0, end);
                    pending.delete(0, end);
                    if (reading) {
                        reader.printAbove(lines);
                    } else {
                        terminal.writer().write(lines);
                    }
                }
                if (reading && !pending.isEmpty()) {
                    // 读取期间出现的提示语：事件轮之后重画的同一个提示符丢弃，
                    // 其他的（如队友的确认请求）作为一行插到上方，用户在当前输入行回答。
                    if (!pending.toString().equals(prompt)) {
                        reader.printAbove(pending.toString());
                    }
                    pending.setLength(0);
                }
                terminal.flush();
            }
        }

        /** 开始读取：输出已完成的行，交出还没换行的提示语。 */
        String beginRead() {
            synchronized (lock) {
                flush();
                prompt = pending.toString();
                pending.setLength(0);
                reading = true;
                return prompt;
            }
        }

        void endRead() {
            synchronized (lock) {
                reading = false;
            }
        }

        void title(String text) {
            synchronized (lock) {
                writeControl((titled ? "" : SAVE_TITLE) + "\u001b]0;" + text + "\u0007");
                titled = true;
            }
        }

        /** 不占屏幕位置的控制序列：JLine 正在读取时经它的锁写出，否则直接写。 */
        private void writeControl(String sequence) {
            if (closed) {
                return;
            }
            if (reading) {
                control = sequence;
                try {
                    reader.callWidget(CONTROL_WIDGET);
                    return;
                } catch (IllegalStateException e) {
                    // JLine 还没开始或已经结束这次读取，没有并发的重画，直接写。
                }
            }
            terminal.writer().write(sequence);
            terminal.flush();
        }

        @Override
        public void close() {
            synchronized (lock) {
                flush();
                if (titled) {
                    writeControl(RESTORE_TITLE);
                }
                closed = true;
            }
        }
    }

    /**
     * 每次重画前按输入刷新命令列表：显示时放在输入行下方（post 区域），并开启鼠标跟踪以接收滚轮；
     * 不显示时关闭跟踪，终端照常选择文字、滚动回看。post 正被 JLine 自己使用（如 Ctrl+R 搜索）时不显示列表。
     * 列表的状态只在 JLine 的锁下读写：按键、重画和其他线程的 printAbove 都拿着这把锁。
     */
    private static final class MenuReader extends LineReaderImpl {
        private final SlashMenu menu;
        private final Supplier<AttributedString> view;
        private boolean tracking;
        /** 结束读取前的最后一次重画不带列表，留在屏幕上的只有输入行。 */
        private boolean finishing;

        MenuReader(Terminal terminal, SlashMenu menu) {
            super(terminal, "hu", new HashMap<>());
            this.menu = menu;
            this.view = menu::render;
        }

        /** 列表正显示在输入行下方。 */
        boolean showing() {
            return post == view;
        }

        @Override
        protected void redisplay(boolean flush) {
            lock.lock();
            try {
                boolean show = !finishing
                        && menu.update(buf.toString(), buf.cursor() == buf.length(), size.getColumns(), size.getRows())
                        && (post == null || post == view);
                if (show) {
                    post = view;
                } else if (post == view) {
                    post = null;
                }
                if (show != tracking) {
                    tracking = show;
                    terminal.trackMouse(show ? Terminal.MouseTracking.Normal : Terminal.MouseTracking.Off);
                }
                super.redisplay(flush);
            } finally {
                lock.unlock();
            }
        }

        @Override
        protected void doCleanup(boolean nl) {
            finishing = true;
            try {
                super.doCleanup(nl);
            } finally {
                finishing = false;
            }
        }
    }
}
