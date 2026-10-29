package dev.huagent.loop;

import dev.huagent.app.ToolUseMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.tool.ToolRegistry;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.*;

class ChatLoopTest {
    @TempDir
    Path root;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        registry = ToolUseMain.createTools(new AgentConfig("http://localhost", "unused", "fake", root,
                10, Duration.ofSeconds(5), 12000));
    }

    @Test
    void executesDifferentToolsSequentiallyAndKeepsCallIdsAndHistory() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("\n开始\n刚才做了什么\nexit\n不应发送\n", (messages, definitions) -> {
            assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob", "grep"),
                    definitions.stream().map(ToolDefinition::name).toList());
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    call("write", "write_file", Map.of("path", "note.txt", "content", "初稿")),
                    call("edit", "edit_file", Map.of("path", "note.txt", "old_text", "初稿", "new_text", "终稿")),
                    call("read", "read_file", Map.of("path", "note.txt")),
                    call("find", "glob", Map.of("pattern", "**/*.txt"))))
                    : new ChatMessage("assistant", "完成");
        }, 10);
        assertEquals(3, requests.size());
        List<ChatMessage> results = requests.get(1).subList(3, 7);
        assertEquals(List.of("write", "edit", "read", "find"),
                results.stream().map(ChatMessage::toolCallId).toList());
        assertEquals("     1\t终稿", results.get(2).content());
        assertEquals("note.txt", results.get(3).content());
        assertEquals("终稿", Files.readString(root.resolve("note.txt")));
        assertEquals(List.of("system", "user", "assistant", "tool", "tool", "tool", "tool", "assistant", "user"),
                requests.get(2).stream().map(ChatMessage::role).toList());
        assertTrue(output.contains("工具请求> write_file [write]"));
        assertTrue(output.contains("工具结果> [read]      1\t终稿"));
    }

    @Test
    void failedToolsReturnResultsAndModelCanCorrectArguments() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        run("执行\n", (messages, definitions) -> {
            requests.add(messages);
            return switch (requests.size()) {
                case 1 -> ChatMessage.assistant(null, List.of(
                        call("unknown", "missing", Map.of()),
                        call("invalid", "write_file", Map.of("path", "note.txt")),
                        call("missing", "read_file", Map.of("path", "note.txt"))));
                case 2 -> ChatMessage.assistant(null, List.of(
                        call("fixed", "write_file", Map.of("path", "note.txt", "content", "修正"))));
                default -> new ChatMessage("assistant", "完成");
            };
        }, 10);
        assertTrue(requests.get(1).subList(3, 6).stream().allMatch(m -> m.content().startsWith("工具错误")));
        assertEquals("fixed", requests.get(2).get(7).toolCallId());
        assertEquals("修正", Files.readString(root.resolve("note.txt")));
    }

    @Test
    void limitPreventsLastBatchFromMutatingFilesAndCompletesProtocol() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("执行\n继续\n", (messages, definitions) -> {
            requests.add(messages);
            return requests.size() <= 2 ? ChatMessage.assistant(null, List.of(
                    call("write-" + requests.size(), "write_file",
                            Map.of("path", "file-" + requests.size(), "content", "x"))))
                    : new ChatMessage("assistant", "完成");
        }, 2);
        assertTrue(Files.exists(root.resolve("file-1")));
        assertFalse(Files.exists(root.resolve("file-2")));
        assertEquals("write-2", requests.get(2).get(5).toolCallId());
        assertTrue(requests.get(2).get(5).content().contains("工具未执行"));
        assertTrue(requests.get(1).get(3).content().contains("[程序提示] 本轮已用到第 2 次模型调用（上限）"),
                "最后一次请求前告诉模型工具不会再执行");
        assertTrue(output.contains("上限（2 次）"));
    }

    @Test
    void emptyReplyIsNotRecordedAndModelIsAskedToContinue() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("执行\n下一轮\n", (messages, definitions) -> {
            requests.add(messages);
            return switch (requests.size()) {
                case 1 -> new ChatMessage("assistant", "");
                case 2 -> new ChatMessage("assistant", "做完了");
                default -> new ChatMessage("assistant", "好");
            };
        }, 10);
        assertEquals(3, requests.size());
        ChatMessage retried = requests.get(1).get(requests.get(1).size() - 1);
        assertEquals("user", retried.role());
        assertEquals("执行\n\n" + ChatLoop.EMPTY_REPLY_NOTICE, retried.content(), "提醒附在上一条消息末尾，空回复不记录");
        assertTrue(output.contains("助手> 做完了"), output);
        assertEquals(List.of("system", "user", "assistant", "user"),
                requests.get(2).stream().map(ChatMessage::role).toList());
    }

    @Test
    void emptyRepliesAreRetriedAtMostTwicePerTurn() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        run("执行\n", (messages, definitions) -> {
            requests.add(messages);
            return new ChatMessage("assistant", "");
        }, 10);
        assertEquals(1 + ChatLoop.MAX_EMPTY_RETRIES, requests.size());
    }

    @Test
    void repeatingTheSameCallGetsARedirectNotice() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        Files.writeString(root.resolve("a.txt"), "内容");
        run("执行\n", (messages, definitions) -> {
            requests.add(messages);
            return requests.size() <= 3
                    ? ChatMessage.assistant(null, List.of(call("r" + requests.size(), "read_file", Map.of("path", "a.txt"))))
                    : new ChatMessage("assistant", "结束");
        }, 10);
        List<ChatMessage> last = requests.get(3);
        assertFalse(last.get(last.size() - 3).content().contains("[程序提示]"), "第二次相同调用还不提醒");
        assertEquals("     1\t内容\n\n" + ChatLoop.repeatedCallNotice("read_file", 3), last.get(last.size() - 1).content());
    }

    @Test
    void lastCallLetsModelSummarizeInsteadOfDroppingWork() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("执行\n", (messages, definitions) -> {
            requests.add(messages);
            return requests.size() == 1
                    ? ChatMessage.assistant("先写文件", List.of(call("w", "write_file", Map.of("path", "a", "content", "x"))))
                    : new ChatMessage("assistant", "已写好 a，还剩测试没跑");
        }, 2);
        assertEquals(2, requests.size());
        assertTrue(output.contains("助手> 先写文件"), "中间说明也显示：" + output);
        assertTrue(output.contains("助手> 已写好 a，还剩测试没跑"), output);
        assertTrue(output.contains("提示> 本轮已用完 2 次模型调用；输入“继续”接着做。"), output);
        assertFalse(output.contains("最后一批工具未执行"), output);
    }

    @Test
    void requestFailureAfterWriteKeepsEffectsAndProtocolHistory() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("执行\n继续\n", (messages, definitions) -> {
            requests.add(messages);
            if (requests.size() == 1) {
                return ChatMessage.assistant(null, List.of(
                        call("write", "write_file", Map.of("path", "note.txt", "content", "已写入"))));
            }
            if (requests.size() == 2) {
                throw new HttpTimeoutException("timeout");
            }
            return new ChatMessage("assistant", "完成");
        }, 10);
        assertEquals("已写入", Files.readString(root.resolve("note.txt")));
        assertEquals(List.of("system", "user", "assistant", "tool", "user"),
                requests.get(2).stream().map(ChatMessage::role).toList());
        assertTrue(output.contains("已保留工具调用和结果"));
    }

    @Test
    void failureBeforeToolsDiscardsTurn() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("失败\n重试\n", (messages, definitions) -> {
            requests.add(messages);
            if (requests.size() == 1) {
                throw new IOException("HTTP 500");
            }
            return new ChatMessage("assistant", "完成");
        }, 10);
        assertEquals(2, requests.get(1).size());
        assertEquals("重试", requests.get(1).get(1).content());
        assertTrue(output.contains("本轮未保存"));
    }

    @Test
    void exitAndEofDoNotRequestModel() throws Exception {
        ModelClient unexpected = (messages, definitions) -> {
            fail("不应调用模型");
            return null;
        };
        run(" quit \n", unexpected, 10);
        run("", unexpected, 10);
    }

    @Test
    void interruptionStopsLoopAndRestoresFlag() throws Exception {
        try {
            String output = run("执行\n不应继续\n", (messages, definitions) -> {
                throw new InterruptedException();
            }, 10);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(output.contains("中断"));
        } finally {
            Thread.interrupted();
        }
    }

    private ToolCall call(String id, String name, Map<String, Object> args) {
        return new ToolCall(id, name, JSON.toJSONString(args));
    }

    private String run(String input, ModelClient client, int maxCalls) throws IOException {
        StringWriter output = new StringWriter();
        new ChatLoop(client, registry, maxCalls, ToolUseMain.SYSTEM_PROMPT)
                .run(new BufferedReader(new StringReader(input)), new PrintWriter(output));
        return output.toString();
    }
}
