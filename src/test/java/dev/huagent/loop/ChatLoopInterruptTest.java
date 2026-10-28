package dev.huagent.loop;

import dev.huagent.harness.TurnInterrupt;
import dev.huagent.hook.AgentHooks;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.prompt.PromptStyle;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.tool.ToolRegistry;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 用户中断（Esc / Ctrl+C）：本轮留在历史里并补全协议，回到提示符继续对话。 */
class ChatLoopInterruptTest {
    private final TurnInterrupt interrupt = new TurnInterrupt();
    /** 阻塞调用开始后按下中断，模拟行编辑器的按键线程。 */
    private final CountDownLatch blocking = new CountDownLatch(1);

    @AfterEach
    void clearFlag() {
        Thread.interrupted();
    }

    @Test
    void interruptDuringAModelRequestKeepsTheTurnAndContinues() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("第一句\n继续\n", List.of(), (messages, tools) -> {
            requests.add(List.copyOf(messages));
            if (requests.size() == 1) {
                block();
            }
            return new ChatMessage("assistant", "好的");
        });

        assertEquals(2, requests.size());
        List<ChatMessage> next = requests.get(1);
        assertEquals(List.of("system", "user", "user", "user"), next.stream().map(ChatMessage::role).toList());
        assertEquals("第一句", next.get(1).content());
        assertEquals(ChatLoop.INTERRUPTED_MARKER, next.get(2).content());
        assertEquals("继续", next.get(3).content());
        assertTrue(output.contains("已中断 · 接下来要做什么？"), output);
        assertTrue(output.contains("助手> 好的"), output);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void interruptDuringAToolGivesEveryCallAResult() throws Exception {
        AtomicInteger otherRuns = new AtomicInteger();
        List<AgentTool> tools = List.of(tool("slow", () -> {
            block();
            return "完成";
        }), tool("other", () -> {
            otherRuns.incrementAndGet();
            return "不应执行";
        }));
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("执行\n继续\n", tools, (messages, definitions) -> {
            requests.add(List.copyOf(messages));
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    new ToolCall("a", "slow", "{}"), new ToolCall("b", "other", "{}")))
                    : new ChatMessage("assistant", "好的");
        });

        assertEquals(0, otherRuns.get());
        List<ChatMessage> next = requests.get(1);
        assertEquals(List.of("system", "user", "assistant", "tool", "tool", "user", "user"),
                next.stream().map(ChatMessage::role).toList());
        assertEquals("a", next.get(3).toolCallId());
        assertEquals(ChatLoop.TOOL_INTERRUPTED, next.get(3).content());
        assertEquals("b", next.get(4).toolCallId());
        assertEquals(ChatLoop.TOOL_SKIPPED, next.get(4).content());
        assertEquals(ChatLoop.INTERRUPTED_MARKER, next.get(5).content());
        assertTrue(output.contains("已中断"), output);
    }

    @Test
    void interruptThatAToolSwallowsStopsBeforeTheNextCall() throws Exception {
        AtomicInteger otherRuns = new AtomicInteger();
        List<AgentTool> tools = List.of(tool("deny", () -> {
            // 如确认问答里按 Esc：工具以拒绝结束，中断只留下请求。
            interrupt.request();
            Thread.interrupted();
            return "用户未批准本次调用。";
        }), tool("other", () -> {
            otherRuns.incrementAndGet();
            return "不应执行";
        }));
        List<List<ChatMessage>> requests = new ArrayList<>();
        run("执行\n继续\n", tools, (messages, definitions) -> {
            requests.add(List.copyOf(messages));
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    new ToolCall("a", "deny", "{}"), new ToolCall("b", "other", "{}")))
                    : new ChatMessage("assistant", "好的");
        });

        assertEquals(0, otherRuns.get());
        List<ChatMessage> next = requests.get(1);
        assertEquals("用户未批准本次调用。", next.get(3).content());
        assertEquals(ChatLoop.TOOL_SKIPPED, next.get(4).content());
        assertEquals(ChatLoop.INTERRUPTED_MARKER, next.get(5).content());
    }

    @Test
    void otherInterruptionsStillEndTheConversation() throws Exception {
        String output = run("执行\n不应继续\n", List.of(), (messages, tools) -> {
            throw new InterruptedException();
        });
        assertTrue(Thread.currentThread().isInterrupted());
        assertTrue(output.contains("请求已中断，对话结束。"), output);
    }

    /** 等待到被中断为止；先通知按键线程按下中断。 */
    private void block() throws InterruptedException {
        blocking.countDown();
        Thread.sleep(10_000);
        fail("应被中断");
    }

    private String run(String input, List<AgentTool> tools, ModelClient client) throws Exception {
        Thread keys = new Thread(() -> {
            try {
                blocking.await();
                interrupt.request();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        keys.setDaemon(true);
        keys.start();
        StringWriter output = new StringWriter();
        new ChatLoop(client, new ToolRegistry(tools), 10, "系统", AgentHooks.empty(), null, null, null, null, null,
                null, null, true, PromptStyle.plain(), interrupt)
                .run(new BufferedReader(new StringReader(input)), new PrintWriter(output));
        return output.toString();
    }

    private static AgentTool tool(String name, Body body) {
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, name, new JSONObject());
            }

            @Override
            public String execute(String arguments) throws IOException, InterruptedException {
                return body.run();
            }
        };
    }

    private interface Body {
        String run() throws IOException, InterruptedException;
    }
}
