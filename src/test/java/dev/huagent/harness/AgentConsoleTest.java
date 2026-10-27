package dev.huagent.harness;

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

/** S15：单 Agent 控制台的用户行、程序事件哨兵行与确认输入互不干扰。 */
class AgentConsoleTest {
    @Test
    void promptsReturnsUserLinesAndWakesOnEvents() throws Exception {
        PipedWriter writer = new PipedWriter();
        PipedReader reader = new PipedReader(writer, 2048);
        StringWriter out = new StringWriter();
        AgentConsole console = new AgentConsole(new BufferedReader(reader, 2048),
                new PrintWriter(out, true));
        try {
            // 没有输入时程序事件以哨兵行唤醒。
            console.notifyEvents();
            BufferedReader prompts = console.prompts();
            CompletableFuture<String> woken = CompletableFuture.supplyAsync(() -> {
                try {
                    return prompts.readLine();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            assertEquals(TurnEvents.WAKE_LINE, woken.get(2, TimeUnit.SECONDS));

            // 之后的用户行原样返回。
            writer.write("你好\n");
            assertEquals("你好", prompts.readLine());
        } finally {
            writer.close();
            console.close();
        }
    }

    @Test
    void answersSkipsWakeEventsAndReturnsOnlyUserLines() throws Exception {
        PipedWriter writer = new PipedWriter();
        PipedReader reader = new PipedReader(writer, 2048);
        StringWriter out = new StringWriter();
        AgentConsole console = new AgentConsole(new BufferedReader(reader, 2048),
                new PrintWriter(out, true));
        try {
            console.notifyEvents();
            writer.write("y\n");
            BufferedReader answers = console.answers();
            CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> {
                try {
                    return answers.readLine();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            assertEquals("y", answer.get(2, TimeUnit.SECONDS));
        } finally {
            writer.close();
            console.close();
        }
    }

    @Test
    void endOfInputEndsBothViews() throws Exception {
        PipedWriter writer = new PipedWriter();
        PipedReader reader = new PipedReader(writer, 2048);
        StringWriter out = new StringWriter();
        AgentConsole console = new AgentConsole(new BufferedReader(reader, 2048),
                new PrintWriter(out, true));
        try {
            writer.close();
            BufferedReader prompts = console.prompts();
            BufferedReader answers = console.answers();
            CompletableFuture<String> ended = CompletableFuture.supplyAsync(() -> {
                try {
                    prompts.readLine();
                    return answers.readLine();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            assertNull(ended.get(2, TimeUnit.SECONDS));
        } finally {
            console.close();
        }
    }
}
