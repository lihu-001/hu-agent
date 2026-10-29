package dev.huagent.prompt;

import dev.huagent.command.CommandResult;
import dev.huagent.command.SlashCommand;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 用 JLine 的流终端模拟按键：键盘写进管道，屏幕输出收集到字节数组。 */
class LineEditorTest {
    /** xterm 的方向键序列（JLine 发出 keypad_xmit 后终端使用应用模式）。 */
    private static final String DOWN = "\u001bOB";
    private static final String UP = "\u001bOA";
    private static final String LEFT = "\u001bOD";
    /** 括号粘贴模式下终端包在粘贴内容前后的标记。 */
    private static final String PASTE_BEGIN = "\u001b[200~";
    private static final String PASTE_END = "\u001b[201~";
    /** JLine 开启、关闭按键鼠标跟踪时写出的序列（1000：按键与滚轮）。 */
    private static final String TRACK_MOUSE = "\u001b[?1000h";
    private static final String UNTRACK_MOUSE = "\u001b[?1000l";

    private static final String ESC = "\u001b";
    /** frame() 的状态栏开头。用 ASCII 分隔线：xterm 测试终端里 JLine 会把 ─ 换成线框字符集。 */
    private static final String RULE = "-- m --";

    private PipedOutputStream keyboard;
    private ByteArrayOutputStream screen;
    private Terminal terminal;
    private LineEditor editor;
    /** 连按判断用的时钟，由测试推进。 */
    private final AtomicLong clock = new AtomicLong();

    @BeforeEach
    void open() throws IOException {
        keyboard = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(keyboard, 4096);
        screen = new ByteArrayOutputStream();
        terminal = TerminalBuilder.builder().system(false).streams(in, screen).type("xterm")
                .stdinEncoding(StandardCharsets.UTF_8).stdoutEncoding(StandardCharsets.UTF_8)
                .size(new Size(80, 24)).build();
        List<SlashCommand> commands = new ArrayList<>();
        commands.add(SlashCommand.local("status", "查看状态", (a, c) -> CommandResult.print("")));
        for (int i = 1; i <= 15; i++) {
            commands.add(SlashCommand.local("cmd%02d".formatted(i), "命令 " + i, (a, c) -> CommandResult.print("")));
        }
        editor = new LineEditor(terminal, () -> commands, clock::get);
    }

    @AfterEach
    void close() throws IOException {
        keyboard.close();
        editor.close();
    }

    @Test
    void inputIsReturnedVerbatim() throws Exception {
        CompletableFuture<String> line = read("A> ");
        // 反斜杠、!! 和不成对的引号都原样返回：不做历史展开，也不因引号未闭合而等待续行。
        type("C:\\Users\\x 吧!! don't \"q\r");
        assertEquals("C:\\Users\\x 吧!! don't \"q", line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void unfinishedPromptIsHeldUntilReadingStartsAndThenDrawnByTheEditor() throws Exception {
        PrintWriter output = editor.output();
        output.println("上一轮的回复");
        output.print("仅允许本次调用？[y/N] ");
        output.flush();
        assertTrue(screen().contains("上一轮的回复"), "整行立即输出");
        assertFalse(screen().contains("[y/N]"), "提示语等读取开始时作为提示符画出");

        CompletableFuture<String> line = read("");
        awaitScreen("[y/N]");
        type("y\r");
        assertEquals("y", line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void percentSignsInThePromptAreShownVerbatim() throws Exception {
        // 不转义时 JLine 会把 %Y 等丢掉、把 %{…%} 之间的文字隐藏。
        CompletableFuture<String> line = read("shell：date +%Y-%m-%d %{x%} 100% [y/N] ");
        type("y\r");
        assertEquals("y", line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void outputWhileReadingIsPrintedAboveAndThePromptIsRedrawn() throws Exception {
        PrintWriter output = editor.output();
        CompletableFuture<String> line = read("❯ ");
        type("hel");
        awaitScreen("hel");

        output.println("后台> bg_1 completed");
        // 事件轮之后重画的同一个提示符不再重复输出。
        output.print("❯ ");
        output.flush();
        String shown = awaitScreen("后台> bg_1 completed");
        assertTrue(shown.lastIndexOf("hel") > shown.indexOf("后台> bg_1 completed"), "输入行在输出下方重画：" + shown);

        type("lo\r");
        assertEquals("hello", line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void marginAppliesToTheConversationButNotToTheInputBox() throws Exception {
        editor.margin(new Margin("●", "⎿"));
        PrintWriter output = editor.output();
        output.println("启动页");
        output.flush();
        awaitScreen("启动页");

        editor.taskRunning(true);
        output.println("后台> bg_1 completed");
        // 80 列的终端每行最多 79 列：第一行排下 "  提示> " 与 35 个字，续行与正文对齐。
        output.println("提示> " + "很长".repeat(40));
        output.flush();
        awaitScreen("  后台> bg_1 completed");
        awaitScreen("\n  长很长");
        CompletableFuture<String> answer = read("【权限确认】shell：ls  [y/N] ");
        awaitScreen("  【权限确认】shell：ls  [y/N] ");
        type("y\r");
        assertEquals("y", answer.get(5, TimeUnit.SECONDS));
        editor.taskRunning(false);

        CompletableFuture<String> line = read("❯ ");
        output.println("队友> 完成");
        output.flush();
        awaitScreen("  队友> 完成");
        type("x\r");
        assertEquals("x", line.get(5, TimeUnit.SECONDS));
        assertFalse(screen().contains("  启动页"), "任务以外的输出原样：" + screen());
        assertFalse(screen().contains("  ❯"), "输入框的提示符原样：" + screen());
    }

    @Test
    void titleIsSavedOnFirstUseAndRestoredOnClose() throws Exception {
        editor.title("✅ hu 就绪 · x");
        editor.title("⏳ hu 运行中\u001b · x");
        String shown = awaitScreen("\u001b]0;⏳ hu 运行中  · x\u0007");
        assertTrue(shown.contains("\u001b[22;0t\u001b]0;✅ hu 就绪 · x\u0007"), "第一次设置前压栈：" + shown);
        assertEquals(1, shown.split("\u001b\\[22;0t", -1).length - 1, "只压栈一次：" + shown);

        editor.close();
        assertTrue(screen().contains("\u001b]0;\u0007\u001b[23;0t"), "关闭时清空并出栈恢复：" + screen());
    }

    @Test
    void titleWhileReadingKeepsTheTypedInput() throws Exception {
        CompletableFuture<String> line = read("A> ");
        type("ab");
        awaitScreen("ab");
        editor.title("⏳ hu 运行中 · x");
        awaitScreen("\u001b]0;⏳ hu 运行中 · x\u0007");
        type("c\r");
        assertEquals("abc", line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void closeWithoutTitleLeavesTheTitleAlone() {
        editor.close();
        assertFalse(screen().contains("\u001b[23;0t"), screen());
    }

    @Test
    void ctrlCClearsTheLineAndASecondPressEndsInput() throws Exception {
        CompletableFuture<String> line = read("A> ");
        type("丢掉这行\u0003");
        awaitScreen(LineEditor.EXIT_HINT);
        type("保留\r");
        assertEquals("保留", line.get(5, TimeUnit.SECONDS));

        // 隔得太久不算连按：只清空并再次提示，800 ms 内再按才结束输入。
        advance(DoublePress.WINDOW_MILLIS + 1);
        CompletableFuture<String> ended = read("B> ");
        int from = screen().length();
        type("\u0003");
        awaitScreen(LineEditor.EXIT_HINT, from);
        assertFalse(ended.isDone());
        type("\u0003");
        assertNull(ended.get(5, TimeUnit.SECONDS));
    }

    @Test
    void escapeTwiceClearsTheLineAndKeepsItInHistory() throws Exception {
        CompletableFuture<String> line = read("A> ");
        type("草稿");
        type(ESC);
        awaitScreen(LineEditor.CLEAR_HINT);
        assertFalse(line.isDone());
        type(ESC);
        // 单独的 Esc 要等 JLine 的歧义超时才生效。
        Thread.sleep(300);
        type("新\r");
        assertEquals("新", line.get(5, TimeUnit.SECONDS));

        CompletableFuture<String> again = read("B> ");
        type(UP + UP + "\r");
        assertEquals("草稿", again.get(5, TimeUnit.SECONDS));
    }

    @Test
    void escapeOnAnEmptyLineDoesNothingAndAPauseStartsOver() throws Exception {
        CompletableFuture<String> line = read("A> ");
        type(ESC);
        Thread.sleep(300);
        assertFalse(screen().contains(LineEditor.CLEAR_HINT));
        type("保留" + ESC);
        awaitScreen(LineEditor.CLEAR_HINT);
        advance(DoublePress.WINDOW_MILLIS + 1);
        type(ESC);
        Thread.sleep(300);
        type("\r");
        assertEquals("保留", line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void escapeAndCtrlCDuringATaskInterruptItInRawMode() throws Exception {
        CountDownLatch interrupts = new CountDownLatch(2);
        editor.onInterrupt(interrupts::countDown);
        editor.taskRunning(true);
        assertFalse(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ECHO), "任务期间不回显");
        type(ESC);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (interrupts.getCount() > 1 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        // 紧跟在 Esc 后的字符会和它组成 Alt 组合键，等 Esc 处理完再按 Ctrl+C。
        type("\u0003");
        assertTrue(interrupts.await(5, TimeUnit.SECONDS));
        editor.taskRunning(false);
        assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ECHO), "任务结束后恢复终端模式");
    }

    @Test
    void textTypedDuringATaskIsFilledIntoTheNextPrompt() throws Exception {
        CountDownLatch interrupts = new CountDownLatch(1);
        editor.onInterrupt(interrupts::countDown);
        editor.taskRunning(true);
        // 方向键等按键序列整个丢弃，不算 Esc；退格删掉一个字，回车不提交。
        type("abc" + UP + "\u007f吧\r");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!editor.typedAhead().equals("ab吧") && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        editor.taskRunning(false);
        CompletableFuture<String> line = read("B> ");
        awaitScreen("ab吧");
        type("!\r");
        assertEquals("ab吧!", line.get(5, TimeUnit.SECONDS));
        assertEquals(1, interrupts.getCount());
    }

    @Test
    void escapeInAConfirmationDuringATaskDeclinesAndInterrupts() throws Exception {
        CountDownLatch interrupts = new CountDownLatch(1);
        editor.onInterrupt(interrupts::countDown);
        editor.taskRunning(true);
        CompletableFuture<String> answer = read("[y/N] ");
        type("y" + ESC);
        assertEquals("", answer.get(5, TimeUnit.SECONDS));
        assertTrue(interrupts.await(5, TimeUnit.SECONDS));
        editor.taskRunning(false);
    }

    @Test
    void ctrlCTwiceDuringATaskEndsInputAtTheNextPrompt() throws Exception {
        CountDownLatch interrupts = new CountDownLatch(2);
        editor.onInterrupt(interrupts::countDown);
        editor.taskRunning(true);
        type("\u0003\u0003");
        assertTrue(interrupts.await(5, TimeUnit.SECONDS));
        editor.taskRunning(false);
        assertNull(read("").get(5, TimeUnit.SECONDS));
    }

    @Test
    void pinnedStyleWritesOnlyTheMarkerAndABlankLineAfterInput() {
        PromptStyle style = editor.pin(frame());
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        style.begin(out);
        style.end(out, "你好");
        // 空行马上回到输入框，输入结束时什么都不写。
        style.end(out, " ");
        style.end(out, null);
        out.flush();
        assertEquals("❯ " + System.lineSeparator(), text.toString());
    }

    @Test
    void pinnedBoxStaysBelowOutputAndSubmittedInputStaysInTheConversation() throws Exception {
        PromptStyle style = editor.pin(frame());
        PrintWriter output = editor.output();
        CompletableFuture<String> line = readPinned(style);
        type("hel");
        awaitScreen("hel");

        int from = screen().length();
        output.println("后台> bg_1 completed");
        output.flush();
        String shown = awaitScreen("后台> bg_1 completed", from);
        int notice = shown.indexOf("后台> bg_1 completed", from);
        assertTrue(shown.indexOf(RULE, notice) > notice, "状态栏与输入行在输出下方重画：" + shown);

        from = screen().length();
        type("lo\r");
        assertEquals("hello", line.get(5, TimeUnit.SECONDS));
        // 测试终端把每个 \n 写成 \r\n，System.lineSeparator() 在 Windows 上成了 \r\r\n，只看紧跟的回车；
        // 输入框里的输入行后面跟的是光标移动，不会出现。
        awaitScreen("❯ hello\r", from);
    }

    @Test
    void duringATaskTheBoxStaysAndEnterQueuesTheInputForTheNextPrompt() throws Exception {
        PromptStyle style = editor.pin(frame());
        PrintWriter output = editor.output();
        editor.taskRunning(true);
        awaitScreen(LineEditor.RUNNING);
        type("下一步\r");
        awaitScreen(LineEditor.QUEUED + "下一步");
        type("再");
        awaitScreen("再");

        int from = screen().length();
        output.println("● 回复");
        output.flush();
        String shown = awaitScreen("● 回复", from);
        int reply = shown.indexOf("● 回复", from);
        assertTrue(shown.indexOf(LineEditor.RUNNING, reply) > reply, "任务期间的输入框在输出下方重画：" + shown);

        endTask();
        // 回到输入框时不读终端，直接取出排队的输入，留在对话里。
        from = screen().length();
        assertEquals("下一步", startPinned(style).get(5, TimeUnit.SECONDS));
        awaitScreen("❯ 下一步\r", from);
        // 没提交的文字填进下一次的输入行。
        CompletableFuture<String> line = readPinned(style);
        type("来\r");
        assertEquals("再来", line.get(5, TimeUnit.SECONDS));
        assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ECHO), "任务结束后恢复终端模式");
    }

    @Test
    void escapeInTheTaskBoxInterruptsAndPutsQueuedInputBack() throws Exception {
        CountDownLatch interrupts = new CountDownLatch(1);
        editor.onInterrupt(interrupts::countDown);
        PromptStyle style = editor.pin(frame());
        editor.taskRunning(true);
        awaitScreen(LineEditor.RUNNING);
        type("改一下\r");
        awaitScreen(LineEditor.QUEUED + "改一下");
        type(ESC);
        assertTrue(interrupts.await(5, TimeUnit.SECONDS));
        endTask();

        // 中断后排队的输入不自动发送，放回输入行。
        CompletableFuture<String> line = readPinned(style);
        assertFalse(line.isDone());
        type("\r");
        assertEquals("改一下", line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void confirmationDuringATaskTakesTheTerminalAndTheBoxComesBack() throws Exception {
        PromptStyle style = editor.pin(frame());
        editor.taskRunning(true);
        awaitScreen(LineEditor.RUNNING);
        type("ab");
        awaitScreen("ab");

        CompletableFuture<String> answer = read("[y/N] ");
        int from = screen().length();
        type("y\r");
        assertEquals("y", answer.get(5, TimeUnit.SECONDS));
        awaitScreen(LineEditor.RUNNING, from);
        awaitScreen("ab", from);

        endTask();
        CompletableFuture<String> line = readPinned(style);
        type("c\r");
        assertEquals("abc", line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void ctrlCTwiceInTheTaskBoxEndsInputAtTheNextPrompt() throws Exception {
        CountDownLatch interrupts = new CountDownLatch(2);
        editor.onInterrupt(interrupts::countDown);
        PromptStyle style = editor.pin(frame());
        editor.taskRunning(true);
        awaitScreen(LineEditor.RUNNING);
        type("\u0003\u0003");
        assertTrue(interrupts.await(5, TimeUnit.SECONDS));
        endTask();
        assertNull(startPinned(style).get(5, TimeUnit.SECONDS));
    }

    @Test
    void ctrlDOnAnEmptyLineEndsInput() throws Exception {
        CompletableFuture<String> line = read("A> ");
        type("\u0004");
        assertNull(line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void tabCompletesASlashCommand() throws Exception {
        CompletableFuture<String> line = read("A> ");
        type("/st\t\r");
        assertEquals("/status ", line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void downArrowSelectsAmongCommandsButBrowsesHistoryOtherwise() throws Exception {
        CompletableFuture<String> first = read("A> ");
        type("上一条\r");
        assertEquals("上一条", first.get(5, TimeUnit.SECONDS));

        // 命令前缀后按 ↓：选中候选，第一次回车填入，第二次回车提交。
        CompletableFuture<String> command = read("B> ");
        type("/st" + DOWN + "\r\r");
        assertEquals("/status ", command.get(5, TimeUnit.SECONDS));

        // 空行上 ↑ 照常翻历史，取回最近一条（JLine 记历史时去掉了行尾空格）；没有选中项时回车直接提交。
        CompletableFuture<String> recalled = read("C> ");
        type(UP + "\r");
        assertEquals("/status", recalled.get(5, TimeUnit.SECONDS));
    }

    /** 列表的版面见 SlashMenuTest；JLine 重画时只写出变化的字符，这里只看结果。 */
    @Test
    void slashListShowsTenCommandsAndArrowsScrollIt() throws Exception {
        CompletableFuture<String> line = read("A> ");
        type("/cmd");
        awaitScreen("1-10 / 1");
        assertFalse(screen().contains("/cmd11"), "第 11 个候选要滚动才出现：" + screen());

        type(DOWN.repeat(11) + "\r\r");
        assertEquals("/cmd11 ", line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void mouseWheelScrollsTheList() throws Exception {
        CompletableFuture<String> line = read("A> ");
        type("/cmd");
        awaitScreen(TRACK_MOUSE);
        // SGR 编码的滚轮事件：65 向下，64 向上。滚到第 4 行开始，↓ 选中可见的第一行。
        String wheelDown = "\u001b[<65;5;3M";
        String wheelUp = "\u001b[<64;5;3M";
        type(wheelDown.repeat(4) + wheelUp + DOWN + "\r\r");
        assertEquals("/cmd04 ", line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void mouseIsTrackedOnlyWhileTheListIsShown() throws Exception {
        CompletableFuture<String> prose = read("A> ");
        type("看 /cmd\r");
        assertEquals("看 /cmd", prose.get(5, TimeUnit.SECONDS));
        assertFalse(screen().contains(TRACK_MOUSE), "不在行首的 / 不显示列表：" + screen());

        CompletableFuture<String> line = read("B> ");
        type("/cmd");
        // 上一次读取结束时 JLine 自己也会关闭跟踪，只看开启之后的输出。
        int shown = awaitScreen(TRACK_MOUSE).length();
        // 输入空格后不再是命令前缀，列表收起，终端恢复自己的鼠标操作（选择文字、滚动回看）。
        type(" x");
        awaitScreen(UNTRACK_MOUSE, shown);
        type("\r");
        assertEquals("/cmd x", line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void multiLinePasteShowsOnePlaceholderAndSubmitsTheText() throws Exception {
        CompletableFuture<String> line = read("A> ");
        type("看看" + PASTE_BEGIN + "第一行\r第二行\n第三行\n" + PASTE_END);
        awaitScreen("[粘贴文本 #1，共 3 行]");
        assertFalse(screen().contains("第二行"), "原文不画在输入行里：" + screen());
        type("\r");
        assertEquals("看看第一行\n第二行\n第三行", line.get(5, TimeUnit.SECONDS));

        // 历史里是占位，↑ 取回后提交同样换回原文。
        CompletableFuture<String> recalled = read("B> ");
        type(UP + "\r");
        assertEquals("看看第一行\n第二行\n第三行", recalled.get(5, TimeUnit.SECONDS));
    }

    @Test
    void backspaceDeletesAPastedPlaceholderWhole() throws Exception {
        CompletableFuture<String> line = read("A> ");
        type("看" + PASTE_BEGIN + "a\nb" + PASTE_END + "\u007f\u007f吧\r");
        assertEquals("吧", line.get(5, TimeUnit.SECONDS));
    }

    @Test
    void keysArrivingTogetherOnAWindowsConsoleArePastedNotSubmitted() throws Exception {
        editor = new LineEditor(terminal, List::of, clock::get, true);
        CompletableFuture<String> line = read("A> ");
        // Windows 控制台没有括号粘贴时，粘贴的文字连同回车、Tab 逐个作为按键一起送来。
        type("第一行\r\t第二行\r");
        awaitScreen("[粘贴文本 #1，共 2 行]");
        assertFalse(line.isDone(), "粘贴里的回车不提交");
        // 单独按下的回车照常提交。
        type("\r");
        assertEquals("第一行\n\t第二行", line.get(5, TimeUnit.SECONDS));

        // 一起送来的按键序列（方向键）在粘贴处停下，照常作为按键处理。
        CompletableFuture<String> edited = read("B> ");
        type("ac" + LEFT + "b");
        Thread.sleep(300);
        type("\r");
        assertEquals("abc", edited.get(5, TimeUnit.SECONDS));
    }

    /**
     * 先打印提示语再开始读取，等提示符画出来才返回：此时终端已进入 raw 模式，之后的按键才按编辑器的规则处理
     * （更早到达的 Ctrl+C 会被终端当作信号吞掉）。prompt 为空时提示语由调用方事先打印并等待。
     */
    private CompletableFuture<String> read(String prompt) throws InterruptedException {
        if (!prompt.isEmpty()) {
            editor.output().print(prompt);
            editor.output().flush();
        }
        BufferedReader input = editor.input();
        CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
            try {
                return input.readLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        if (!prompt.isEmpty()) {
            awaitScreen(prompt);
        }
        return line;
    }

    /** 固定输入框的状态栏：分隔线按 20 列画，状态里只有模型名 m。 */
    private static FramedPrompt frame() {
        return new FramedPrompt("❯ ", '-', () -> 20, "m", 0);
    }

    /** 固定输入框时等待输入：样式写出 "❯ " 后开始读取，等输入框画出来才返回（此时终端已进入 raw 模式）。 */
    private CompletableFuture<String> readPinned(PromptStyle style) throws InterruptedException {
        int from = screen().length();
        CompletableFuture<String> line = startPinned(style);
        awaitScreen(RULE, from);
        return line;
    }

    /** 样式写出 "❯ " 后开始读取，不等输入框画出（取出排队的输入或结束输入时不画）。 */
    private CompletableFuture<String> startPinned(PromptStyle style) {
        style.begin(editor.output());
        editor.output().flush();
        BufferedReader input = editor.input();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return input.readLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** 任务结束，等监听线程结束任务期间的输入框、恢复终端模式；之后的按键才交给下一次读取。 */
    private void endTask() throws InterruptedException {
        editor.taskRunning(false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ECHO)) {
            if (System.nanoTime() > deadline) {
                fail("任务期间的输入框没有结束：" + screen());
            }
            Thread.sleep(10);
        }
    }

    private void advance(long millis) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    private void type(String keys) throws IOException {
        keyboard.write(keys.getBytes(StandardCharsets.UTF_8));
        keyboard.flush();
    }

    private String screen() {
        synchronized (screen) {
            return screen.toString(StandardCharsets.UTF_8);
        }
    }

    private String awaitScreen(String text) throws InterruptedException {
        return awaitScreen(text, 0);
    }

    /** 等屏幕输出的第 from 个字符之后出现 text。 */
    private String awaitScreen(String text, int from) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (screen().indexOf(text, from) < 0) {
            if (System.nanoTime() > deadline) {
                fail("屏幕上没有出现 " + text + "：" + screen());
            }
            Thread.sleep(10);
        }
        return screen();
    }
}
