package dev.huagent.team;

import dev.huagent.permission.ConfirmPrompt;
import dev.huagent.tool.ToolCall;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TeamConsoleTest {
    private final ToolCall call = new ToolCall("c1", "shell", "{\"command\":\"echo hi\"}");

    @Test
    void promptsReturnTerminalLinesThenNullAtEnd() {
        TeamConsole console = new TeamConsole(new BufferedReader(new StringReader("你好\nexit\n")),
                new PrintWriter(new StringWriter()));
        BufferedReader prompts = console.prompts(() -> null);
        assertEquals("你好", readLine(prompts));
        assertEquals("exit", readLine(prompts));
        assertNull(readLine(prompts));
        assertNull(readLine(prompts), "结束后一直返回 null");
    }

    @Test
    void wakeDeliversMailWithoutUserInput() throws Exception {
        PipedWriter keyboard = new PipedWriter();
        TeamConsole console = new TeamConsole(new BufferedReader(new PipedReader(keyboard)),
                new PrintWriter(new StringWriter()));
        List<String> mailbox = new ArrayList<>(List.of("<team-events>结果</team-events>"));
        BufferedReader prompts = console.prompts(() -> mailbox.isEmpty() ? null : mailbox.remove(0));
        CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> readLine(prompts));
        // 第一条消息在等待前就已经在收件箱里，直接返回。
        assertEquals("<team-events>结果</team-events>", line.get(5, TimeUnit.SECONDS));

        CompletableFuture<String> next = CompletableFuture.supplyAsync(() -> readLine(prompts));
        Thread.sleep(100);
        assertFalse(next.isDone(), "没有输入也没有消息时阻塞");
        mailbox.add("<team-events>第二条</team-events>");
        console.wake();
        assertEquals("<team-events>第二条</team-events>", next.get(5, TimeUnit.SECONDS));
        keyboard.close();
    }

    @Test
    void userLineCarriesMailThatArrivedMeanwhile() {
        TeamConsole console = new TeamConsole(new BufferedReader(new StringReader("继续\n")),
                new PrintWriter(new StringWriter()));
        List<String> mailbox = new ArrayList<>();
        BufferedReader prompts = console.prompts(() -> mailbox.isEmpty() ? null : mailbox.remove(0));
        // 用户行已在队列中；先检查收件箱为空，再取到行，之后附上刚到的消息。
        AtomicReference<String> result = new AtomicReference<>();
        mailbox.add("<team-events>x</team-events>");
        result.set(readLine(prompts));
        assertEquals("<team-events>x</team-events>", result.get(), "已有消息时先交给 Lead");
        assertEquals("继续", readLine(prompts));
    }

    @Test
    void teammateConfirmationIsAskedOnMainThreadAndAnswered() throws Exception {
        StringWriter buffer = new StringWriter();
        PipedWriter keyboard = new PipedWriter();
        TeamConsole console = new TeamConsole(new BufferedReader(new PipedReader(keyboard)), new PrintWriter(buffer));
        BufferedReader prompts = console.prompts(() -> null);
        CompletableFuture<Boolean> approved = CompletableFuture.supplyAsync(() -> confirm(console, "alice"));
        CompletableFuture<String> lead = CompletableFuture.supplyAsync(() -> readLine(prompts));
        awaitText(buffer, "允许队友 alice 本次调用？");
        assertTrue(buffer.toString().contains("[alice] 需要确认"), buffer.toString());
        keyboard.write("y\n");
        keyboard.flush();
        assertTrue(approved.get(5, TimeUnit.SECONDS));
        assertFalse(lead.isDone(), "确认的回答不会成为 Lead 的输入");
        keyboard.write("下一句\n");
        keyboard.flush();
        assertEquals("下一句", lead.get(5, TimeUnit.SECONDS));
        keyboard.close();
    }

    @Test
    void confirmationRedrawsLeadPromptWithConfiguredStyle() throws Exception {
        StringWriter buffer = new StringWriter();
        PipedWriter keyboard = new PipedWriter();
        TeamConsole console = new TeamConsole(new BufferedReader(new PipedReader(keyboard)), new PrintWriter(buffer),
                out -> out.print("[输入] "));
        BufferedReader prompts = console.prompts(() -> null);
        CompletableFuture<Boolean> approved = CompletableFuture.supplyAsync(() -> confirm(console, "alice"));
        CompletableFuture<String> lead = CompletableFuture.supplyAsync(() -> readLine(prompts));
        awaitText(buffer, "允许队友 alice 本次调用？");
        keyboard.write("y\n");
        keyboard.flush();
        assertTrue(approved.get(5, TimeUnit.SECONDS));
        awaitText(buffer, "[输入] ");
        assertFalse(buffer.toString().contains("你> "), buffer.toString());
        keyboard.close();
        assertNull(lead.get(5, TimeUnit.SECONDS));
    }

    @Test
    void compactConfirmationIsOneLine() throws Exception {
        StringWriter buffer = new StringWriter();
        PipedWriter keyboard = new PipedWriter();
        TeamConsole console = new TeamConsole(new BufferedReader(new PipedReader(keyboard)), new PrintWriter(buffer),
                out -> out.print("[输入] "), ConfirmPrompt.compact(false));
        BufferedReader prompts = console.prompts(() -> null);
        CompletableFuture<Boolean> approved = CompletableFuture.supplyAsync(() -> confirm(console, "alice"));
        CompletableFuture<String> lead = CompletableFuture.supplyAsync(() -> readLine(prompts));
        awaitText(buffer, "[y/N] ");
        assertEquals("【权限确认】shell：echo hi  允许队友 alice 本次调用？[y/N] ", buffer.toString());
        keyboard.write("y\n");
        keyboard.flush();
        assertTrue(approved.get(5, TimeUnit.SECONDS));
        keyboard.close();
        assertNull(lead.get(5, TimeUnit.SECONDS));
    }

    @Test
    void confirmationArrivingDuringLeadPromptWaitsUntilLeadReadsAgain() throws Exception {
        StringWriter buffer = new StringWriter();
        PipedWriter keyboard = new PipedWriter();
        TeamConsole console = new TeamConsole(new BufferedReader(new PipedReader(keyboard)), new PrintWriter(buffer));
        BufferedReader answers = console.answers();
        CompletableFuture<String> leadAnswer = CompletableFuture.supplyAsync(() -> readLine(answers));
        CompletableFuture<Boolean> approved = CompletableFuture.supplyAsync(() -> confirm(console, "bob"));
        Thread.sleep(150);
        // Lead 正在等自己的确认回答：队友的请求只排队，不抢走这一行。
        keyboard.write("n\n");
        keyboard.flush();
        assertEquals("n", leadAnswer.get(5, TimeUnit.SECONDS));
        assertFalse(approved.isDone());
        BufferedReader prompts = console.prompts(() -> null);
        CompletableFuture<String> lead = CompletableFuture.supplyAsync(() -> readLine(prompts));
        awaitText(buffer, "允许队友 bob 本次调用？");
        keyboard.write("yes\n");
        keyboard.flush();
        assertTrue(approved.get(5, TimeUnit.SECONDS));
        keyboard.close();
        assertNull(lead.get(5, TimeUnit.SECONDS));
    }

    @Test
    void anythingButYesDeniesAndCloseRejectsWaitingAndFutureRequests() throws Exception {
        PipedWriter keyboard = new PipedWriter();
        StringWriter buffer = new StringWriter();
        TeamConsole console = new TeamConsole(new BufferedReader(new PipedReader(keyboard)), new PrintWriter(buffer));
        BufferedReader prompts = console.prompts(() -> null);
        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> confirm(console, "alice"));
        CompletableFuture.runAsync(() -> readLine(prompts));
        awaitText(buffer, "允许队友 alice");
        keyboard.write("好的\n");
        keyboard.flush();
        assertFalse(first.get(5, TimeUnit.SECONDS));

        CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(() -> confirm(console, "carol"));
        Thread.sleep(100);
        console.close();
        assertFalse(waiting.get(5, TimeUnit.SECONDS), "会话结束时等待中的确认一律拒绝");
        assertFalse(confirm(console, "dave"), "关闭后不再排队");
        keyboard.close();
    }

    @Test
    void endOfInputDeniesQueuedConfirmation() throws Exception {
        PipedWriter keyboard = new PipedWriter();
        TeamConsole console = new TeamConsole(new BufferedReader(new PipedReader(keyboard)),
                new PrintWriter(new StringWriter()));
        BufferedReader prompts = console.prompts(() -> null);
        CompletableFuture<Boolean> approved = CompletableFuture.supplyAsync(() -> confirm(console, "alice"));
        CompletableFuture<String> lead = CompletableFuture.supplyAsync(() -> readLine(prompts));
        Thread.sleep(150);
        keyboard.close();
        assertFalse(approved.get(5, TimeUnit.SECONDS));
        assertNull(lead.get(5, TimeUnit.SECONDS));
    }

    private boolean confirm(TeamConsole console, String member) {
        try {
            return console.confirm(member, "需要确认", call);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readLine(BufferedReader reader) {
        try {
            return reader.readLine();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitText(StringWriter buffer, String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!buffer.toString().contains(text)) {
            if (System.nanoTime() > deadline) {
                fail("没有出现：" + text + "\n" + buffer);
            }
            Thread.sleep(10);
        }
    }
}
