package dev.huagent.hook;

import dev.huagent.app.HooksMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.ToolCall;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleHooksTest {
    @TempDir
    Path root;

    @Test
    void sharesCustomHookAcrossAllFourEventsInOrder() throws Exception {
        List<String> events = new ArrayList<>();
        List<List<ChatMessage>> completed = new ArrayList<>();
        List<List<ChatMessage>> beforeHistories = new ArrayList<>();
        StringWriter buffer = new StringWriter();
        AgentHook hook = new AgentHook() {
            public String userPromptSubmit(String prompt, List<ChatMessage> history) {
                events.add("prompt:" + prompt);
                beforeHistories.add(history);
                assertThrows(UnsupportedOperationException.class, () -> history.add(new ChatMessage("user", prompt)));
                assertTrue(history.stream().noneMatch(message -> prompt.equals(message.content())));
                return null;
            }

            public String preToolUse(ToolCall call) {
                events.add("pre:" + call.id());
                assertFalse(Files.exists(root.resolve("protected/note.txt")));
                assertFalse(buffer.toString().contains("权限确认>"));
                return null;
            }

            public void postToolUse(ToolCall call, String result, long elapsedNanos) throws IOException {
                events.add("post:" + call.id());
                assertEquals("内容", Files.readString(root.resolve("protected/note.txt")));
                assertTrue(buffer.toString().contains("权限确认>"));
            }

            public void stop(List<ChatMessage> turn) {
                events.add("stop");
                completed.add(turn);
                assertEquals("assistant", turn.get(turn.size() - 1).role());
                assertTrue(buffer.toString().contains("助手> " + turn.get(turn.size() - 1).content()));
                assertThrows(UnsupportedOperationException.class, turn::clear);
            }
        };
        AtomicInteger calls = new AtomicInteger();
        run("第一次\ny\n第二次\nexit\n", (messages, definitions) -> {
            int index = calls.incrementAndGet();
            events.add("model:" + index);
            if (index == 1) {
                return ChatMessage.assistant(null, List.of(writeCall()));
            }
            return new ChatMessage("assistant", index == 2 ? "已完成" : "继续回答");
        }, 5, buffer, hook);

        assertEquals(List.of("prompt:第一次", "model:1", "pre:write", "post:write", "model:2",
                "stop", "prompt:第二次", "model:3", "stop"), events);
        assertEquals(1, beforeHistories.get(0).size()); // 不包含尚未记录的第一条用户消息。
        assertEquals(5, beforeHistories.get(1).size());
        assertEquals(List.of("user", "assistant", "tool", "assistant"),
                completed.get(0).stream().map(ChatMessage::role).toList());
        assertEquals(List.of(new ChatMessage("user", "第二次"), new ChatMessage("assistant", "继续回答")),
                completed.get(1)); // Stop 只接收当前轮次。
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "拒绝"})
    void rejectedPromptIsNotRecordedAndRemainingHooksDoNotRun(String reason) throws Exception {
        List<String> laterPrompts = new ArrayList<>();
        List<List<ChatMessage>> requests = new ArrayList<>();
        List<List<ChatMessage>> completed = new ArrayList<>();
        String output = run("被拒绝\n正常\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            return new ChatMessage("assistant", "回答");
        }, 3, new StringWriter(), new AgentHook() {
            public String userPromptSubmit(String prompt, List<ChatMessage> history) {
                return "被拒绝".equals(prompt) ? reason : null;
            }
        }, new AgentHook() {
            public String userPromptSubmit(String prompt, List<ChatMessage> history) {
                laterPrompts.add(prompt);
                return null;
            }
            public void stop(List<ChatMessage> turn) {
                completed.add(turn);
            }
        });
        assertEquals(List.of("正常"), laterPrompts);
        assertEquals(1, requests.size());
        assertEquals(List.of("正常"), requests.get(0).stream().filter(m -> "user".equals(m.role()))
                .map(ChatMessage::content).toList());
        assertEquals(1, completed.size());
        assertTrue(output.contains("提示词未记录，未请求模型"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime"})
    void promptFailureBlocksOnlyCurrentSubmissionAndHidesDetails(String type) throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        String output = run("失败\n正常\nexit\n", (messages, definitions) -> {
            modelCalls.incrementAndGet();
            assertEquals("正常", messages.get(1).content());
            return new ChatMessage("assistant", "回答");
        }, 3, new StringWriter(), new AgentHook() {
            public String userPromptSubmit(String prompt, List<ChatMessage> history) throws IOException {
                if ("失败".equals(prompt)) {
                    throwFailure(type);
                }
                return null;
            }
        });
        assertEquals(1, modelCalls.get());
        assertTrue(output.contains("UserPromptSubmit 前置检查失败"));
        assertFalse(output.contains("private"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime"})
    void stopFailurePreservesAnswerHistoryAndContinuesOtherCallbacks(String type) throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        AtomicInteger laterStops = new AtomicInteger();
        String output = run("第一次\n第二次\nexit\n", (messages, definitions) -> {
            requests.add(messages);
            return new ChatMessage("assistant", "已回答");
        }, 3, new StringWriter(), new AgentHook() {
            public void stop(List<ChatMessage> turn) throws IOException {
                throwFailure(type);
            }
        }, new AgentHook() {
            public void stop(List<ChatMessage> turn) {
                laterStops.incrementAndGet();
            }
        });
        assertEquals(2, requests.size()); // Stop 异常不重试模型。
        assertEquals(2, laterStops.get());
        assertEquals("已回答", requests.get(1).get(2).content());
        assertEquals("第二次", requests.get(1).get(3).content());
        assertTrue(output.contains("Stop 后置回调失败"));
        assertFalse(output.contains("private"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"exit", "quit", "eof"})
    void emptyAndExitInputDoNotTriggerLifecycleEvents(String exit) throws Exception {
        run(" \n\n" + ("eof".equals(exit) ? "" : exit + "\n"),
                (messages, definitions) -> fail("不应请求模型"), 3, new StringWriter(), new AgentHook() {
                    public String userPromptSubmit(String prompt, List<ChatMessage> history) {
                        return fail("空输入和退出不能触发提交事件");
                    }
                    public void stop(List<ChatMessage> turn) {
                        fail("退出不能当作任务完成");
                    }
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "timeout", "limit", "after-tool"})
    void failureOrLimitDoesNotEmitStopAndNextTurnCanComplete(String mode) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<List<ChatMessage>> completed = new ArrayList<>();
        run("失败轮\ny\n下一轮\nexit\n".replace("y\n", "after-tool".equals(mode) ? "y\n" : ""),
                (messages, definitions) -> {
                    int index = requests.incrementAndGet();
                    if (index == 1) {
                        return switch (mode) {
                            case "io" -> throw new IOException("model failed");
                            case "timeout" -> throw new HttpTimeoutException("timeout");
                            default -> ChatMessage.assistant(null, List.of(writeCall()));
                        };
                    }
                    if ("after-tool".equals(mode) && index == 2) {
                        throw new IOException("model failed after write");
                    }
                    return new ChatMessage("assistant", "正常完成");
                }, "limit".equals(mode) ? 1 : 3, new StringWriter(), new AgentHook() {
                    public void stop(List<ChatMessage> turn) {
                        completed.add(turn);
                    }
                });
        assertEquals(1, completed.size());
        assertEquals("下一轮", completed.get(0).get(0).content());
        assertEquals(2, completed.get(0).size());
        assertEquals("after-tool".equals(mode), Files.exists(root.resolve("protected/note.txt")));
    }

    @Test
    void interruptedPromptStopsLoopWithoutModelOrCompletion() throws Exception {
        try {
            String output = run("中断\n下一次\nexit\n", (messages, definitions) -> fail("不应请求模型"),
                    3, new StringWriter(), new AgentHook() {
                        public String userPromptSubmit(String prompt, List<ChatMessage> history)
                                throws InterruptedException {
                            throw new InterruptedException();
                        }
                        public void stop(List<ChatMessage> turn) {
                            fail("中断不能当作完成");
                        }
                    });
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(output.contains("请求已中断"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void warningReceiverFailureDoesNotStopRemainingCompletionHooks() {
        AtomicInteger completed = new AtomicInteger();
        AgentHooks hooks = new AgentHooks(List.of(new AgentHook() {
            public void stop(List<ChatMessage> turn) throws IOException {
                throw new IOException();
            }
        }, new AgentHook() {
            public void stop(List<ChatMessage> turn) {
                completed.incrementAndGet();
            }
        }), message -> { throw new IllegalStateException(); });
        hooks.stop(List.of(new ChatMessage("assistant", "完成")));
        assertEquals(1, completed.get());
    }

    private String run(String text, ModelClient client, int maxCalls, StringWriter buffer, AgentHook... hooks)
            throws IOException {
        BufferedReader input = new BufferedReader(new StringReader(text));
        PrintWriter output = new PrintWriter(buffer);
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake", root,
                maxCalls, Duration.ofSeconds(5), 12000);
        HooksMain.createLoop(config, client, input, output, root, List.of(hooks)).run(input, output);
        return buffer.toString();
    }

    private ToolCall writeCall() {
        return new ToolCall("write", "write_file",
                JSON.toJSONString(Map.of("path", "protected/note.txt", "content", "内容")));
    }

    private void throwFailure(String type) throws IOException {
        if ("io".equals(type)) {
            throw new IOException("private");
        }
        throw new IllegalStateException("private");
    }
}
