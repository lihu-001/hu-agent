package dev.huagent.prompt;

import dev.huagent.command.CommandResult;
import dev.huagent.command.SlashCommand;
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 用 JLine 的流终端模拟按键：键盘写进管道，屏幕输出收集到字节数组。 */
class LineEditorTest {
    private PipedOutputStream keyboard;
    private ByteArrayOutputStream screen;
    private LineEditor editor;

    @BeforeEach
    void open() throws IOException {
        keyboard = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(keyboard, 4096);
        screen = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder().system(false).streams(in, screen).type("xterm")
                .stdinEncoding(StandardCharsets.UTF_8).stdoutEncoding(StandardCharsets.UTF_8)
                .size(new Size(80, 24)).build();
        editor = new LineEditor(terminal, () -> List.of(
                SlashCommand.local("status", "查看状态", (a, c) -> CommandResult.print(""))));
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
    void ctrlCClearsTypedTextAndEndsInputOnAnEmptyLine() throws Exception {
        CompletableFuture<String> line = read("A> ");
        type("丢掉这行\u0003保留\r");
        assertEquals("保留", line.get(5, TimeUnit.SECONDS));

        CompletableFuture<String> ended = read("B> ");
        type("\u0003");
        assertNull(ended.get(5, TimeUnit.SECONDS));
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
        // xterm 的方向键序列（JLine 发出 keypad_xmit 后终端使用应用模式）。
        String down = "\u001bOB";
        String up = "\u001bOA";
        CompletableFuture<String> first = read("A> ");
        type("上一条\r");
        assertEquals("上一条", first.get(5, TimeUnit.SECONDS));

        // 命令前缀后按 ↓：进入候选选择，唯一候选直接填入；回车提交。
        CompletableFuture<String> command = read("B> ");
        type("/st" + down + "\r");
        assertEquals("/status ", command.get(5, TimeUnit.SECONDS));

        // 空行上 ↑ 照常翻历史，取回最近一条（JLine 记历史时去掉了行尾空格）。
        CompletableFuture<String> recalled = read("C> ");
        type(up + "\r");
        assertEquals("/status", recalled.get(5, TimeUnit.SECONDS));
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!screen().contains(text)) {
            if (System.nanoTime() > deadline) {
                fail("屏幕上没有出现 " + text + "：" + screen());
            }
            Thread.sleep(10);
        }
        return screen();
    }
}
