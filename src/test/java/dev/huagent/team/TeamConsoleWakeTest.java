package dev.huagent.team;

import dev.huagent.harness.EventHub;
import dev.huagent.harness.TurnEvents;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** S15：团队模式中 Lead 会被程序事件唤醒，队友消息仍优先于程序事件。 */
class TeamConsoleWakeTest {
    @Test
    void hubEventsWakeLeadWithSentinelWhileMailboxMessagesWin() throws Exception {
        PipedWriter writer = new PipedWriter();
        PipedReader reader = new PipedReader(writer, 2048);
        StringWriter out = new StringWriter();
        TeamConsole console = new TeamConsole(new BufferedReader(reader, 2048), new PrintWriter(out, true));
        EventHub hub = new EventHub();
        hub.addListener(console::wake);
        // 第一段没有队友消息（返回 null），第二段有一条。
        java.util.concurrent.atomic.AtomicInteger phase = new java.util.concurrent.atomic.AtomicInteger();
        try {
            BufferedReader prompts = console.prompts(() -> phase.get() == 1 ? "队友消息" : null, hub);
            CompletableFuture<String> woken = CompletableFuture.supplyAsync(() -> {
                try {
                    return prompts.readLine();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            hub.publish("[s1（备份）] completed");
            assertEquals(TurnEvents.WAKE_LINE, woken.get(2, TimeUnit.SECONDS));
            // 模拟 ChatLoop 在哨兵轮中取走事件，之后的唤醒不再有待送达内容。
            hub.drain();

            // 有队友消息时，唤醒返回的是消息本身而不是哨兵行。
            CompletableFuture<String> mailed = CompletableFuture.supplyAsync(() -> {
                try {
                    return prompts.readLine();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            phase.set(1);
            console.wake();
            assertEquals("队友消息", mailed.get(2, TimeUnit.SECONDS));
        } finally {
            writer.close();
            console.close();
        }
    }

    @Test
    void oldPromptsViewKeepsWorkingWithoutHub() throws Exception {
        PipedWriter writer = new PipedWriter();
        PipedReader reader = new PipedReader(writer, 2048);
        StringWriter out = new StringWriter();
        TeamConsole console = new TeamConsole(new BufferedReader(reader, 2048), new PrintWriter(out, true));
        try {
            writer.write("exit\n");
            assertEquals("exit", console.prompts(() -> null).readLine());
        } finally {
            writer.close();
            console.close();
        }
    }
}
