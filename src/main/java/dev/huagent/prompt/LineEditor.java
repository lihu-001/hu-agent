package dev.huagent.prompt;

import dev.huagent.command.SlashCommand;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Buffer;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Attributes;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.AbstractWindowsTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.NonBlockingReader;

import java.io.BufferedReader;
import java.io.IOError;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * hu 交互会话的行编辑器（JLine）：方向键编辑与历史，斜杠命令补全。行首输入 / 时在输入行下方列出命令（SlashMenu），
 * 继续输入即时过滤；最多显示 10 个，↑↓ 选择、列表跟着滚动，鼠标滚轮也能滚动列表。
 * Tab 把选中的命令（未选中时为第一个）填进输入行，回车在选中后填入，再回车执行。
 * 提供参数候选的命令（如 /model）在 "/命令 " 之后同样列出参数，操作相同（见 SlashCompleter）。
 *
 * <p>输出与读取的配合：ChatLoop 和各种确认先打印提示语（"❯ "、"[y/N] "），再读一行。output 留住还没换行的部分，
 * 读取开始时交给 JLine 当作提示符画出；读取期间其他线程（后台任务、定时计划、队友）的整行输出经 printAbove
 * 插到输入行上方，不打乱正在输入的内容。读取只在有人等待输入时进行（InputPump），模型回复期间不显示提示符。
 *
 * <p>Esc 与 Ctrl+C 与 Claude Code 相同。输入时：输入行有内容时 Esc 连按两次（800 ms 内）清空，清掉的内容进历史、↑ 可找回，
 * 第一次在输入行下方提示再按一次；Ctrl+C 清空输入行并提示再按一次退出，800 ms 内再按结束输入。
 * 任务运行中（taskRunning，见 TurnInterrupt）：Esc 或 Ctrl+C 中断任务，连按两次 Ctrl+C 在任务结束后退出；
 * 确认问答里按下时以空回答（拒绝）结束确认并中断任务。没有固定输入框时任务期间不显示输入行，由监听线程读按键，
 * 键入的文字攒起来，回到提示符时填进输入行。Ctrl+C 一直作为按键读取，不产生信号，Shell 命令与 MCP server 不会收到。
 *
 * <p>固定输入框（pin，hu 交互终端使用）与 Claude Code 相同：输入框（空行、状态栏分隔线与 ❯ 输入行）作为 JLine 的提示符画在
 * 终端最下方，对话输出一律经 printAbove 插到它上方。任务期间输入框照样显示（上方多一行 "运行中…"），由监听线程读取，
 * 键入的文字照常编辑；回车把输入排进队列，任务结束后依次发送。回车提交后输入框收起，输入以 "❯ 输入" 留在对话里。
 *
 * <p>粘贴与 Claude Code 相同：多行或很长的文本在输入行里只显示一行占位，提交时换回原文（PastedText），退格整个删掉占位。
 * 终端支持括号粘贴时按粘贴标记整段读取；Windows 控制台的粘贴可能逐个作为按键送来，按下一个键时后面已有字符在等，
 * 就当作粘贴整段读完（keyBursts），其中的回车是换行而不是提交。
 *
 * <p>取不到系统终端时 open 返回 null，调用方继续用 Console 按行读取。
 */
public final class LineEditor implements AutoCloseable {
    /** 读取期间经 callWidget 写出控制序列：拿着 JLine 的锁，不会插进输入行重画的转义序列中间。 */
    private static final String CONTROL_WIDGET = "hu-control";
    private static final String ESCAPE_WIDGET = "hu-escape";
    /** 提示到期后由计时线程经 callWidget 收起。 */
    private static final String HINT_WIDGET = "hu-hint";
    static final String CLEAR_HINT = "再按一次 Esc 清空输入";
    static final String EXIT_HINT = "再按一次 Ctrl+C 退出";
    /** 任务期间输入框上方的一行。 */
    static final String RUNNING = "运行中…（Esc 中断，回车排队）";
    /** 任务期间排队的输入，每条一行，显示在 RUNNING 下面。 */
    static final String QUEUED = "已排队：";
    private static final AttributedStyle FAINT = AttributedStyle.DEFAULT.faint();
    private static final int ESC = 0x1b;
    private static final int CTRL_C = 0x03;
    /** 任务期间监听线程每次最多等这么久的按键；确认问答开始读取时最多等这么久它交出终端。 */
    private static final long POLL_MILLIS = 50;
    /** 一次粘贴的字符逐个送来时，相邻字符最多间隔这么久；超过就算粘贴结束。 */
    private static final long BURST_GAP_MILLIS = 20;
    /** 第一次设置标题前把原标题压栈（XTPUSHTITLE）。 */
    private static final String SAVE_TITLE = "\u001b[22;0t";
    /** 先清空标题，不支持标题栈的终端由此回到默认标题；支持的终端随后出栈恢复原标题（XTPOPTITLE）。 */
    private static final String RESTORE_TITLE = "\u001b]0;\u0007\u001b[23;0t";

    private final Terminal terminal;
    private final MenuReader reader;
    private final SlashMenu menu;
    private final PromptWriter writer = new PromptWriter();
    private final PrintWriter output = new PrintWriter(writer, true);
    private final LongSupplier nanoTime;
    private final PastedText pasted = new PastedText();
    private final DoublePress escape;
    private final DoublePress interrupt;
    private volatile Runnable interruptTask = () -> { };
    /** 对话区的左边距与折行，见 margin；为 null 时输出原样写出。 */
    private volatile Margin margin;
    /** 固定在终端最下方的输入框，见 pin；为 null 时（默认）提示符照常画在输出后面，任务期间不显示。 */
    private volatile FramedPrompt frame;
    /** 本次读取开始时有任务在运行，即任务中的确认问答；只在读取线程上访问。 */
    private boolean answering;
    /** 正在读的是任务期间的输入框（见 readTaskLine）；由监听线程设置，按键处理据此中断任务、排队输入。 */
    private volatile boolean taskLine;
    private final Thread watcher;

    /** 以下字段由 keys 保护：读取线程、监听线程与报告任务状态的主线程都会访问。 */
    private final Object keys = new Object();
    private boolean task;
    private boolean lineOpen;
    /** 监听线程正在读终端；开始读取一行前等它读完手上这一下。 */
    private boolean polling;
    /** 监听线程正在读任务期间的输入框：要用终端时中断它结束读取，终端模式由它读完后切换。 */
    private boolean taskReading;
    private boolean closed;
    /** 任务期间连按了两次 Ctrl+C：之后的输入提示符直接结束输入。 */
    private boolean quitting;
    /** 任务期间进入原始模式之前的终端模式；为 null 表示没有进入。 */
    private Attributes cooked;
    /** 任务期间键入的文字，回到提示符时填进输入行。 */
    private final StringBuilder typeAhead = new StringBuilder();
    /** 任务期间回车排队的输入（粘贴为占位），回到输入框时依次取出发送。 */
    private final List<String> queued = new ArrayList<>();

    LineEditor(Terminal terminal, Supplier<? extends Collection<SlashCommand>> commands) {
        this(terminal, commands, System::nanoTime);
    }

    /** Windows 控制台按连续送来的按键识别粘贴，见类注释。 */
    LineEditor(Terminal terminal, Supplier<? extends Collection<SlashCommand>> commands, LongSupplier nanoTime) {
        this(terminal, commands, nanoTime, terminal instanceof AbstractWindowsTerminal<?>);
    }

    LineEditor(Terminal terminal, Supplier<? extends Collection<SlashCommand>> commands, LongSupplier nanoTime,
               boolean keyBursts) {
        this.terminal = Objects.requireNonNull(terminal);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.escape = new DoublePress(nanoTime);
        this.interrupt = new DoublePress(nanoTime);
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
        // 任务期间的输入框里回车排队，不结束读取；包在 onMenu 里面，列表显示时回车仍是填入候选。
        Widget accept = reader.getWidgets().get(LineReader.ACCEPT_LINE);
        reader.getWidgets().put(LineReader.ACCEPT_LINE, () -> taskLine ? queue() : accept.apply());
        onMenu(LineReader.ACCEPT_LINE, () -> fill(menu.choice(false)));
        reader.getWidgets().put(LineReader.BEGIN_PASTE, () -> paste(reader.readPaste()));
        Widget backspace = reader.getWidgets().get(LineReader.BACKWARD_DELETE_CHAR);
        reader.getWidgets().put(LineReader.BACKWARD_DELETE_CHAR, () -> deletePlaceholder() || backspace.apply());
        if (keyBursts) {
            // 包在 onMenu 外面：粘贴里的回车与 Tab 先当作文字，不选列表、不提交。
            onBurst(LineReader.SELF_INSERT);
            onBurst(LineReader.ACCEPT_LINE);
            onBurst(LineReader.EXPAND_OR_COMPLETE);
        }
        reader.getWidgets().put(LineReader.MOUSE, this::mouse);
        reader.getWidgets().put(CONTROL_WIDGET, () -> {
            terminal.writer().write(writer.control);
            terminal.flush();
            return true;
        });
        // 单独的 Esc 要等 JLine 的歧义超时（默认 100 ms）确认后面没有按键序列；两下来得更快时读成 ESC ESC。
        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        main.bind(new Reference(ESCAPE_WIDGET), KeyMap.esc(), KeyMap.esc() + KeyMap.esc());
        reader.getWidgets().put(ESCAPE_WIDGET, this::escape);
        // Ctrl+C 在 JLine 的原始模式下是按键（绑定 interrupt），替换掉原来的结束读取。
        reader.getWidgets().put(LineReader.INTERRUPT, this::interruptKey);
        reader.getWidgets().put(HINT_WIDGET, () -> {
            if (reader.hintExpired(nanoTime.getAsLong())) {
                reader.hint(null, 0);
                reader.redisplay(true);
            }
            return true;
        });
        watcher = new Thread(this::watch, "terminal-keys");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** 打开系统终端；不是交互终端或平台不支持时返回 null。 */
    public static LineEditor open(Supplier<? extends Collection<SlashCommand>> commands) {
        // JLine 默认在原始模式下读到 Ctrl+C 时另外模拟一次 INT 信号，读取中会被它打断；这里只要按键。
        System.setProperty(TerminalBuilder.PROP_SOFTWARE_SIGNALS, "false");
        // 固定输入框的分隔线 ─ 原样输出，不换成终端的线框字符集（ESC ( 0）：与写在对话里的字符相同，
        // 输出中途被打断也不会让终端停在线框字符集里。JLine 在第一次用到 AttributedString 时读取，这里在那之前。
        System.setProperty(TerminalBuilder.PROP_DISABLE_ALTERNATE_CHARSET, "true");
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

    /**
     * 每次 readLine 读一行，以 output 中尚未换行的提示语作为提示符；输入结束（空行上 Ctrl+D、连按两次 Ctrl+C）时返回 null。
     * 不在任务中时，任务期间键入的文字预先填进输入行。粘贴的占位换回原文，返回的一行里可能有换行。
     * 固定了输入框时，提示语正是输入框的 "❯ " 就画出输入框；任务期间有排队的输入时不读终端，直接取出最早的一条。
     */
    public BufferedReader input() {
        return new BufferedReader(Reader.nullReader()) {
            @Override
            public String readLine() throws IOException {
                String prompt = writer.beginRead();
                FramedPrompt box = frame != null && prompt.equals(frame.marker()) ? frame : null;
                try {
                    if (!beginLine()) {
                        return null;
                    }
                    String next = box == null ? null : nextQueued();
                    if (next != null) {
                        writer.echo(echo(box, next));
                        return pasted.expand(next);
                    }
                    String typed = answering ? "" : takeTypeAhead();
                    reader.hint(null, 0);
                    reader.box(box == null ? null : () -> idleBox(box));
                    // 任务中的确认问答属于对话区，与上面的输出一样留出左边距；输入框的 ❯ 保持原样。
                    String shown = indent(prompt, answering);
                    while (true) {
                        try {
                            // JLine 把提示符里的 % 当作格式（%N、%{…%} 等，未知的直接丢掉），
                            // 确认提示里有模型给出的命令，必须原样显示。
                            String line = reader.readLine(shown.replace("%", "%%"), null, (Character) null, typed);
                            // 输入框已随读取结束擦掉，空行直接回到输入框，不留在对话里。
                            if (box != null && !line.isBlank()) {
                                writer.echo(echo(box, line));
                            }
                            return pasted.expand(line);
                        } catch (UserInterruptException e) {
                            // 只剩外部发来的 INT 信号（Ctrl+C 是按键，见 interruptKey）：有内容时清掉重读，空行上结束输入。
                            if (e.getPartialLine() == null || e.getPartialLine().isEmpty()) {
                                return null;
                            }
                            typed = null;
                        }
                    }
                } catch (EndOfFileException e) {
                    return null;
                } catch (IOError e) {
                    throw new IOException(e);
                } finally {
                    endLine();
                    writer.endRead();
                }
            }
        };
    }

    /**
     * 对话区的左边距与折行（见 Margin，hu 交互终端用 Transcript.margin）：任务期间的输出、读取期间插到输入行上方的输出
     * 与任务中确认问答的提示语逐行套用，按当前终端宽度折行。输入框（分隔线与 ❯）和启动页不在任务期间输出，保持原样。
     * 为 null 时（默认）输出原样写出。
     */
    public void margin(Margin margin) {
        this.margin = margin;
    }

    /**
     * 与 Claude Code 一样把输入框固定在终端最下方（见类注释），返回会话使用的提示符样式（hu 交互终端在 HuMain 装配）。
     * 输入框是空一行、frame 的分隔线（状态栏）和 "❯ " 输入行，每次重画按当前状态与列数重新生成。
     * 返回的样式 begin 只写出 "❯ "，读取开始时据此认出输入框；end 在读到非空的一行（含程序事件的哨兵行）后空一行，
     * 把回车后留在对话里的输入与回复隔开。在会话开始读取之前调用。
     */
    public PromptStyle pin(FramedPrompt frame) {
        this.frame = Objects.requireNonNull(frame);
        return new PromptStyle() {
            @Override
            public void begin(PrintWriter output) {
                output.print(frame.marker());
            }

            @Override
            public void end(PrintWriter output, String line) {
                if (line != null && !line.isBlank()) {
                    output.println();
                }
            }

            @Override
            public void context(int usedTokens) {
                frame.context(usedTokens);
            }

            @Override
            public void model(String model) {
                frame.model(model);
            }
        };
    }

    /** 任务中按 Esc 或 Ctrl+C 时调用 action（hu 接到 TurnInterrupt.request）；在读按键的线程上调用。 */
    public void onInterrupt(Runnable action) {
        interruptTask = Objects.requireNonNull(action);
    }

    /**
     * 任务开始（true）与结束（false）；见 TurnInterrupt。任务期间没有读取时终端处于原始模式，由监听线程读按键；
     * 正在读取（确认问答，或事件轮开始时用户还在输入）时等读取结束再切换。
     */
    public void taskRunning(boolean running) {
        synchronized (keys) {
            task = running;
            if (!running && taskReading) {
                // 结束任务期间的输入框（见 readTaskLine），没提交的文字留到回到输入框时。
                watcher.interrupt();
            }
            if (!lineOpen) {
                switchMode();
            }
            keys.notifyAll();
        }
    }

    /** 任务期间攒下、回到提示符时填进输入行的文字。 */
    String typedAhead() {
        synchronized (keys) {
            return typeAhead.toString();
        }
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
        synchronized (keys) {
            closed = true;
            if (taskReading) {
                watcher.interrupt();
            }
            keys.notifyAll();
        }
        writer.close();
        closeQuietly(terminal);
    }

    /**
     * 开始读取一行：等监听线程交出终端（任务期间的输入框由此结束），记下是不是任务中的确认问答。
     * 任务期间连按过两次 Ctrl+C、回到提示符时返回 false，不再读取。
     */
    private boolean beginLine() throws InterruptedIOException {
        synchronized (keys) {
            lineOpen = true;
            if (taskReading) {
                watcher.interrupt();
            }
            try {
                while (polling) {
                    keys.wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("等待按键监听结束时被中断");
            }
            answering = task;
            return task || !quitting;
        }
    }

    /** 取出任务期间键入的文字，预先填进输入行。 */
    private String takeTypeAhead() {
        synchronized (keys) {
            String typed = typeAhead.toString();
            typeAhead.setLength(0);
            return typed;
        }
    }

    /** 任务期间排队的输入中最早的一条；没有时返回 null。 */
    private String nextQueued() {
        synchronized (keys) {
            return queued.isEmpty() ? null : queued.removeFirst();
        }
    }

    private void endLine() {
        synchronized (keys) {
            lineOpen = false;
            answering = false;
            switchMode();
            keys.notifyAll();
        }
    }

    /** transcript 为 true（属于对话区）且设置了 margin 时按当前终端宽度套用左边距与折行，否则原样返回。 */
    private String indent(String text, boolean transcript) {
        Margin current = margin;
        return current == null || !transcript ? text : current.apply(text, columns());
    }

    private boolean running() {
        synchronized (keys) {
            return task;
        }
    }

    /**
     * 没有读取时按任务状态切换终端模式：任务期间为原始模式（不回显、按键逐个可读）。调用方持有 keys，且没有读取。
     * 任务期间的输入框还在读时不切换：JLine 结束读取时会恢复它开始时的模式，由监听线程读完后再切换。
     */
    private void switchMode() {
        if (closed || taskReading) {
            // 终端关闭时已恢复原来的模式。
            return;
        }
        if (task && cooked == null) {
            cooked = terminal.enterRawMode();
        } else if (!task && cooked != null) {
            terminal.setAttributes(cooked);
            cooked = null;
        }
    }

    /**
     * Esc：任务期间的输入框里中断任务，排队的输入放回输入行；确认问答里中断任务；
     * 输入行有内容时连按两次清空（清掉的内容进历史），第一次提示再按一次。
     */
    private boolean escape() {
        if (taskLine) {
            interruptTask.run();
            unqueue();
            return true;
        }
        if (answering) {
            return cancelAnswer();
        }
        String text = reader.getBuffer().toString();
        if (text.isEmpty()) {
            escape.reset();
            return true;
        }
        // 两下来得比 JLine 的歧义超时还快时读成一个 ESC ESC 序列，算连按。
        if (!KeyMap.esc().equals(reader.getLastBinding()) || escape.press()) {
            escape.reset();
            reader.getHistory().add(text);
            reader.getBuffer().clear();
            reader.hint(null, 0);
        } else {
            hint(CLEAR_HINT);
        }
        return true;
    }

    /**
     * Ctrl+C：任务期间的输入框里与 Esc 相同，连按两次时任务结束后退出；确认问答里中断任务；
     * 否则清空输入行并提示再按一次退出，800 ms 内再按结束输入。
     */
    private boolean interruptKey() {
        if (taskLine) {
            pressInterruptInTask();
            interruptTask.run();
            unqueue();
            return true;
        }
        if (answering) {
            pressInterruptInTask();
            return cancelAnswer();
        }
        if (interrupt.press()) {
            return reader.exit();
        }
        reader.getBuffer().clear();
        hint(EXIT_HINT);
        return true;
    }

    /** 任务中的确认问答：先请求中断（TurnInterrupt 等回答读完再中断任务线程），再以空回答（拒绝）结束这次确认。 */
    private boolean cancelAnswer() {
        interruptTask.run();
        reader.getBuffer().clear();
        return reader.submit();
    }

    /** 任务中按 Ctrl+C：连按两次时记下退出，任务结束后的提示符直接结束输入。 */
    private void pressInterruptInTask() {
        if (interrupt.press()) {
            synchronized (keys) {
                quitting = true;
            }
        }
    }

    /** 在输入行下方显示一行提示，与连按的等待时间一起到期，到期后收起。在 JLine 的锁下调用。 */
    private void hint(String text) {
        long deadline = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(DoublePress.WINDOW_MILLIS);
        reader.hint(text, deadline);
        CompletableFuture.delayedExecutor(DoublePress.WINDOW_MILLIS + 20, TimeUnit.MILLISECONDS).execute(() -> {
            try {
                reader.callWidget(HINT_WIDGET);
            } catch (IllegalStateException e) {
                // 读取已经结束，提示随输入行一起收起了。
            }
        });
    }

    /** 监听线程：任务期间且没有读取时，固定了输入框就读任务期间的输入框（见 readTaskLine），否则逐个读按键（见 key）。 */
    private void watch() {
        NonBlockingReader in = terminal.reader();
        while (true) {
            String typed = null;
            synchronized (keys) {
                while (!closed && (!task || lineOpen)) {
                    try {
                        keys.wait();
                    } catch (InterruptedException e) {
                        // 结束输入框的中断只在 taskReading 时发出，这里收到的已经过时。
                    }
                }
                if (closed) {
                    return;
                }
                polling = true;
                if (frame != null) {
                    taskReading = true;
                    typed = typeAhead.toString();
                    typeAhead.setLength(0);
                }
            }
            boolean ended = false;
            String left = "";
            try {
                if (typed != null) {
                    left = readTaskLine(typed);
                } else {
                    int c = in.read(POLL_MILLIS);
                    ended = c == NonBlockingReader.EOF;
                    if (c >= 0) {
                        key(in, c);
                    }
                }
            } catch (IOException e) {
                // 终端不可读：不再监听，任务仍可正常结束。
                ended = true;
            } finally {
                synchronized (keys) {
                    typeAhead.append(left);
                    polling = false;
                    taskReading = false;
                    // 结束输入框的中断可能在读取结束之后才到，清掉，不留给下一次读取。
                    Thread.interrupted();
                    if (!lineOpen) {
                        switchMode();
                    }
                    keys.notifyAll();
                }
            }
            if (ended) {
                return;
            }
        }
    }

    /**
     * 任务期间的输入框（见 pin），在监听线程上读：输入框上方多一行 RUNNING，键入的文字照常编辑与显示。
     * Esc 与 Ctrl+C 中断任务（见 escape、interruptKey），回车把输入排进队列（见 queue）。
     * 任务结束或开始确认问答时，taskRunning、beginLine 中断本线程结束读取（JLine 读按键时响应线程中断），
     * 返回输入行里还没提交的文字，回到输入框时填进输入行。
     */
    private String readTaskLine(String typed) throws IOException {
        FramedPrompt box = frame;
        writer.taskReading(true);
        reader.hint(null, 0);
        reader.box(() -> taskBox(box));
        taskLine = true;
        try {
            // 回车以外结束读取的按键（如 Ctrl+O）同样把输入排进队列。
            queue(reader.readLine(box.marker(), null, (Character) null, typed));
            return "";
        } catch (UserInterruptException e) {
            return e.getPartialLine() == null ? "" : e.getPartialLine();
        } catch (EndOfFileException e) {
            // 空行上的 Ctrl+D 在任务期间不结束输入；终端输入已经结束时不再监听。
            Thread.interrupted();
            if (terminal.reader().peek(1) == NonBlockingReader.EOF) {
                throw new IOException("终端输入已结束", e);
            }
            return "";
        } catch (IOError e) {
            throw new IOException(e);
        } finally {
            taskLine = false;
            writer.taskReading(false);
        }
    }

    /** 等待输入时的输入框：空一行、分隔线（状态栏）、"❯ " 输入行。 */
    private static AttributedString idleBox(FramedPrompt box) {
        return new AttributedString("\n" + box.rule() + "\n" + box.marker());
    }

    /** 任务期间的输入框：空一行、RUNNING、排队的输入（每条一行，放不下时从中间省略）、分隔线、"❯ " 输入行。 */
    private AttributedString taskBox(FramedPrompt box) {
        List<String> waiting;
        synchronized (keys) {
            waiting = List.copyOf(queued);
        }
        String head = Margin.INDENT + QUEUED;
        int room = columns() - 1 - FramedPrompt.displayWidth(head);
        AttributedStringBuilder text = new AttributedStringBuilder().append("\n");
        text.styled(FAINT, Margin.INDENT + RUNNING);
        for (String line : waiting) {
            text.append("\n").styled(FAINT, head + FramedPrompt.shorten(TerminalText.visible(line), room));
        }
        return text.append("\n").append(box.rule()).append("\n").append(box.marker()).toAttributedString();
    }

    /** 回车提交后留在对话里的输入：空一行，"❯ " 加输入行里显示的文字（粘贴为占位），多行时续行与第一行对齐。 */
    private static String echo(FramedPrompt box, String shown) {
        String newline = System.lineSeparator();
        String pad = " ".repeat(FramedPrompt.displayWidth(box.marker()));
        return newline + box.marker() + shown.replace("\n", newline + pad) + newline;
    }

    /** 任务期间的输入框里回车：输入排进队列，输入行清空。 */
    private boolean queue() {
        queue(reader.getBuffer().toString());
        reader.getBuffer().clear();
        return true;
    }

    /** 非空的输入（含粘贴的占位）记进历史、排进队列，输入框上方随即显示。 */
    private void queue(String text) {
        if (text.isBlank()) {
            return;
        }
        reader.getHistory().add(text);
        synchronized (keys) {
            queued.add(text);
        }
    }

    /** 中断任务时排队的输入不再自动发送：以空格连起来放回输入行，排在已键入的文字前面。 */
    private void unqueue() {
        String waiting;
        synchronized (keys) {
            if (queued.isEmpty()) {
                return;
            }
            waiting = String.join(" ", queued);
            queued.clear();
        }
        Buffer buffer = reader.getBuffer();
        String typed = buffer.toString();
        buffer.clear();
        buffer.write(typed.isBlank() ? waiting : waiting + " " + typed);
    }

    /** 任务期间的一个按键：Esc 与 Ctrl+C 中断任务；可见字符与退格攒成回到提示符时的输入，其他按键丢弃。 */
    private void key(NonBlockingReader in, int c) throws IOException {
        if (c == CTRL_C) {
            pressInterruptInTask();
            interruptTask.run();
        } else if (c == ESC) {
            // 方向键等按键序列以 Esc 开头、后面紧跟其余字符；单独的 Esc 之后在歧义超时内没有字符。
            int next = in.read(LineReaderImpl.DEFAULT_AMBIGUOUS_BINDING);
            if (next < 0 || next == ESC) {
                interruptTask.run();
            } else {
                skipSequence(in, next);
            }
        } else {
            synchronized (keys) {
                typed(c);
            }
        }
    }

    /** 读完一个按键序列：CSI（Esc [ … 终止字符）与 SS3（Esc O x）；其余是 Alt 组合键，第二个字符已经读掉。 */
    private static void skipSequence(NonBlockingReader in, int next) throws IOException {
        if (next == 'O') {
            in.read(LineReaderImpl.DEFAULT_AMBIGUOUS_BINDING);
        } else if (next == '[') {
            for (int i = 0; i < 32; i++) {
                int c = in.read(LineReaderImpl.DEFAULT_AMBIGUOUS_BINDING);
                if (c < 0 || c >= 0x40 && c <= 0x7e) {
                    return;
                }
            }
        }
    }

    /** 调用方持有 keys。退格删掉最后一个字（含代理对），回车与其他控制字符丢弃。 */
    private void typed(int c) {
        int length = typeAhead.length();
        if (c == 0x7f || c == '\b') {
            if (length > 0) {
                boolean pair = length > 1 && Character.isSurrogatePair(typeAhead.charAt(length - 2),
                        typeAhead.charAt(length - 1));
                typeAhead.setLength(length - (pair ? 2 : 1));
            }
        } else if (!Character.isISOControl(c)) {
            typeAhead.append((char) c);
        }
    }

    /** 命令列表显示时按键先交给 action；列表不显示或 action 不处理（返回 false）时照常执行原来的功能。 */
    private void onMenu(String widget, BooleanSupplier action) {
        Widget original = reader.getWidgets().get(widget);
        reader.getWidgets().put(widget, () -> reader.showing() && action.getAsBoolean() || original.apply());
    }

    /** 粘贴的文字插入光标处，长文本折叠成占位（见 PastedText）。 */
    private boolean paste(String text) {
        reader.getBuffer().write(pasted.insert(text));
        return true;
    }

    /** 光标前是粘贴的占位时退格整个删掉，返回 true；否则返回 false，照常删一个字。 */
    private boolean deletePlaceholder() {
        Buffer buffer = reader.getBuffer();
        int length = pasted.endingIn(buffer.substring(0, buffer.cursor()));
        return length > 0 && buffer.backspace(length) > 0;
    }

    /** 按键之后马上还有字符在等时，连同这个按键当作一次粘贴（见 MenuReader.readBurst），否则照常执行原来的功能。 */
    private void onBurst(String widget) {
        Widget original = reader.getWidgets().get(widget);
        reader.getWidgets().put(widget, () -> {
            String burst = reader.readBurst(BURST_GAP_MILLIS);
            return burst == null ? original.apply() : paste(burst);
        });
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
        /** 监听线程正在读任务期间的输入框：整行输出插到输入框上方，不足一行的留着（可能是确认问答的提示语）。 */
        private boolean taskReading;
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
                    // 读取期间插到上方的是其他线程的输出（后台、定时、队友），与任务期间的输出一样属于对话区。
                    if (reading || taskReading) {
                        reader.printAbove(indent(lines, true));
                    } else {
                        terminal.writer().write(indent(lines, running()));
                    }
                }
                if (reading && !pending.isEmpty()) {
                    // 读取期间出现的提示语：事件轮之后重画的同一个提示符丢弃，
                    // 其他的（如队友的确认请求）作为一行插到上方，用户在当前输入行回答。
                    if (!pending.toString().equals(prompt)) {
                        reader.printAbove(indent(pending.toString(), true));
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

        void taskReading(boolean on) {
            synchronized (lock) {
                taskReading = on;
            }
        }

        /** 输入框收起后写出留在对话里的输入（见 LineEditor.echo），不套用左边距。 */
        void echo(String text) {
            synchronized (lock) {
                if (!closed) {
                    terminal.writer().write(text);
                    terminal.flush();
                }
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
            if (reading || taskReading) {
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
     * 连按提示（如 "再按一次 Esc 清空输入"）也放在 post，显示期间代替列表。
     * 列表与提示的状态只在 JLine 的锁下读写：按键、重画和其他线程的 printAbove、callWidget 都拿着这把锁。
     * 固定的输入框（见 box）同样在每次重画前重新生成，作为提示符。
     */
    private static final class MenuReader extends LineReaderImpl {
        private final SlashMenu menu;
        private final Supplier<AttributedString> view;
        private final Supplier<AttributedString> hintView;
        /** 这次读取的输入框；为 null 时提示符照常。 */
        private Supplier<AttributedString> box;
        private AttributedString hint;
        private long hintDeadline;
        private boolean tracking;
        /** 结束读取前的最后一次重画不带列表，留在屏幕上的只有输入行。 */
        private boolean finishing;

        MenuReader(Terminal terminal, SlashMenu menu) {
            super(terminal, "hu", new HashMap<>());
            this.menu = menu;
            this.view = menu::render;
            this.hintView = () -> hint;
        }

        /** 列表正显示在输入行下方。 */
        boolean showing() {
            return post == view;
        }

        /** 显示（text 为 null 时收起）输入行下方的提示，deadline（nanoTime）之后由 hintExpired 判断到期。 */
        void hint(String text, long deadline) {
            lock.lock();
            try {
                hint = text == null ? null : new AttributedString(text, AttributedStyle.DEFAULT.faint());
                hintDeadline = deadline;
            } finally {
                lock.unlock();
            }
        }

        boolean hintExpired(long now) {
            return hint != null && now - hintDeadline >= 0;
        }

        /**
         * 设置下一次读取的输入框：每次重画按它重新生成提示符（状态栏随列数与状态变化），
         * 读取结束时连同输入一起擦掉，不留在屏幕上。box 为 null 时提示符照常，结束时留在屏幕上。
         */
        void box(Supplier<AttributedString> box) {
            lock.lock();
            try {
                this.box = box;
                option(Option.ERASE_LINE_ON_FINISH, box != null);
            } finally {
                lock.unlock();
            }
        }

        /** 以当前输入（可以为空）结束这次读取。 */
        boolean submit() {
            return acceptLine();
        }

        /** 结束这次读取，readLine 抛出 EndOfFileException，与空行上按 Ctrl+D 相同。 */
        boolean exit() {
            state = State.EOF;
            return true;
        }

        /** 括号粘贴：开始标记已经读掉，读到结束标记为止。 */
        String readPaste() {
            return doReadStringUntil(BRACKETED_PASTE_END);
        }

        /**
         * 刚读到的按键之后马上（1 ms 内）还有字符在等，就是一次粘贴：连同这个按键读完整段并返回，
         * 相邻字符间隔超过 gapMillis 时结束；否则返回 null。遇到 Esc 等控制字符停下，留给按键绑定处理。
         */
        String readBurst(long gapMillis) {
            String key = getLastBinding();
            if (key == null || !pastable(peekCharacter(1))) {
                return null;
            }
            StringBuilder text = new StringBuilder(key);
            while (pastable(peekCharacter(gapMillis))) {
                text.appendCodePoint(readCharacter());
            }
            return text.toString();
        }

        private static boolean pastable(int c) {
            return c >= 0 && (c == '\t' || c == '\r' || c == '\n' || !Character.isISOControl(c));
        }

        @Override
        protected void redisplay(boolean flush) {
            lock.lock();
            try {
                // 结束时 JLine 先清空提示符再重画，以此擦掉输入框；这次不再生成。
                if (box != null && !finishing) {
                    prompt = box.get();
                }
                // 输入框在输入行上方多占的行不留给列表，矮终端里列表不把输入框顶出屏幕。
                int rows = size.getRows();
                int above = (int) prompt.toString().chars().filter(c -> c == '\n').count();
                int height = rows > 0 ? Math.max(1, rows - above) : rows;
                boolean free = post == null || post == view || post == hintView;
                boolean note = !finishing && hint != null && free;
                boolean show = !finishing && !note
                        && menu.update(buf.toString(), buf.cursor() == buf.length(), size.getColumns(), height)
                        && free;
                if (note) {
                    post = hintView;
                } else if (show) {
                    post = view;
                } else if (post == view || post == hintView) {
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
