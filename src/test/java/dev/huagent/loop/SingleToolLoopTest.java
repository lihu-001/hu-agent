package dev.huagent.loop;

import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SingleToolLoopTest {
    @Test
    void executesMultipleCallsAndSendsMatchingResultsBeforeContinuing() throws IOException {
        List<List<ChatMessage>> requests = new ArrayList<>();
        List<String> executed = new ArrayList<>();
        AgentTool tool = fakeTool(executed);
        StringWriter output = new StringWriter();
        new ChatLoop((messages, tools) -> {
            assertEquals("shell", tools.get(0).name());
            requests.add(messages);
            if (requests.size() == 1) {
                return ChatMessage.assistant("先查看目录", List.of(
                        new ToolCall("call-1", "shell", "first"),
                        new ToolCall("call-2", "shell", "second")));
            }
            return new ChatMessage("assistant", "完成");
        }, tool, 10).run(new BufferedReader(new StringReader("查看目录\n继续\n")), new PrintWriter(output));

        assertEquals(List.of("first", "second"), executed);
        List<ChatMessage> followup = requests.get(1);
        assertEquals(List.of("system", "user", "assistant", "tool", "tool"),
                followup.stream().map(ChatMessage::role).toList());
        assertEquals("call-1", followup.get(3).toolCallId());
        assertEquals("result:first", followup.get(3).content());
        assertEquals("call-2", followup.get(4).toolCallId());
        assertEquals(List.of("system", "user", "assistant", "tool", "tool", "assistant", "user"),
                requests.get(2).stream().map(ChatMessage::role).toList());
        assertTrue(output.toString().contains("工具请求> shell [call-1]"));
        assertTrue(output.toString().contains("工具结果> [call-2]"));
        String log = output.toString();
        assertTrue(log.contains("模型请求> 第 1 次请求中..."));
        assertTrue(log.contains("模型请求> 第 2 次请求中..."));
        assertTrue(log.matches("(?s).*模型响应> 耗时 \\d+ ms，工具调用 2 个.*"));
        assertTrue(log.indexOf("模型请求> 第 1 次") < log.indexOf("模型响应>"));
        assertTrue(log.indexOf("模型响应>") < log.indexOf("工具请求> shell [call-1]"));
    }

    @Test
    void unknownToolAndExecutionFailureAreReturnedToModel() throws IOException {
        List<List<ChatMessage>> requests = new ArrayList<>();
        AgentTool broken = new AgentTool() {
            public ToolDefinition definition() {
                return fakeTool(new ArrayList<>()).definition();
            }

            public String execute(String arguments) throws IOException {
                throw new IOException("private filesystem details");
            }
        };
        new ChatLoop((messages, tools) -> {
            requests.add(messages);
            return requests.size() == 1 ? ChatMessage.assistant(null, List.of(
                    new ToolCall("a", "missing", "{}"), new ToolCall("b", "shell", "{}")))
                    : new ChatMessage("assistant", "工具失败");
        }, broken, 10).run(new BufferedReader(new StringReader("执行\n")), new PrintWriter(new StringWriter()));
        assertTrue(requests.get(1).get(3).content().contains("未知工具"));
        assertTrue(requests.get(1).get(4).content().contains("工具错误"));
        assertFalse(requests.get(1).get(4).content().contains("private"));
    }

    @Test
    void modelFailureAfterExecutionPreservesToolHistory() throws IOException {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("执行\n下一步\n", (messages, tools) -> {
            requests.add(messages);
            if (requests.size() == 1) {
                return ChatMessage.assistant(null, List.of(new ToolCall("a", "missing", "{}")));
            }
            if (requests.size() == 2) {
                throw new HttpTimeoutException("timeout");
            }
            return new ChatMessage("assistant", "恢复");
        });
        assertEquals(List.of("system", "user", "assistant", "tool", "user"),
                requests.get(2).stream().map(ChatMessage::role).toList());
        assertEquals("a", requests.get(2).get(3).toolCallId());
        assertTrue(output.contains("已保留工具调用和结果"));
    }

    @Test
    void stopsAtModelCallLimitWithoutExecutingLastBatchAndCanStartNextTurn() throws IOException {
        List<List<ChatMessage>> requests = new ArrayList<>();
        List<String> executed = new ArrayList<>();
        StringWriter output = new StringWriter();
        new ChatLoop((messages, tools) -> {
            requests.add(messages);
            return requests.size() <= 2 ? ChatMessage.assistant(null,
                    List.of(new ToolCall("call-" + requests.size(), "shell", "command")))
                    : new ChatMessage("assistant", "继续成功");
        }, fakeTool(executed), 2).run(new BufferedReader(new StringReader("执行\n继续\n")), new PrintWriter(output));
        assertEquals(3, requests.size());
        assertEquals(1, executed.size());
        assertEquals("call-2", requests.get(2).get(5).toolCallId());
        assertTrue(requests.get(2).get(5).content().contains("工具未执行"));
        assertTrue(output.toString().contains("上限（2 次）"));
        assertTrue(output.toString().contains("继续成功"));
    }

    @Test
    void timeoutBeforeToolsDiscardsTurn() throws IOException {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("超时\n恢复\n", (messages, tools) -> {
            requests.add(messages);
            if (requests.size() == 1) {
                throw new HttpTimeoutException("timeout");
            }
            return new ChatMessage("assistant", "完成");
        });
        assertEquals(2, requests.get(1).size());
        assertTrue(output.contains("本轮未保存"));
    }

    private AgentTool fakeTool(List<String> executed) {
        return new AgentTool() {
            public ToolDefinition definition() {
                return new ToolDefinition("shell", "测试工具", new com.alibaba.fastjson2.JSONObject());
            }

            public String execute(String arguments) {
                executed.add(arguments);
                return "result:" + arguments;
            }
        };
    }

    @Test
    void retainsSuccessfulTurnsAndSkipsBlankInput() throws IOException {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("\n我叫小李\n我叫什么？\nexit\n不会发送\n", (messages, tools) -> {
            requests.add(List.copyOf(messages));
            return new ChatMessage("assistant", requests.size() == 1 ? "你好，小李" : "你叫小李");
        });

        assertEquals(2, requests.size());
        assertEquals(List.of("system", "user", "assistant", "user"),
                requests.get(1).stream().map(ChatMessage::role).toList());
        assertEquals("我叫小李", requests.get(1).get(1).content());
        assertEquals("你好，小李", requests.get(1).get(2).content());
        assertTrue(output.contains("你叫小李"));
    }

    @Test
    void failedTurnIsNotAddedToHistoryAndNextInputStillWorks() throws IOException {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("失败的问题\n新的问题\n", (messages, tools) -> {
            requests.add(List.copyOf(messages));
            if (requests.size() == 1) {
                throw new IOException("模型接口返回 HTTP 401");
            }
            return new ChatMessage("assistant", "恢复成功");
        });

        assertEquals(2, requests.get(1).size());
        assertEquals("新的问题", requests.get(1).get(1).content());
        assertTrue(output.contains("HTTP 401"));
        assertTrue(output.contains("恢复成功"));
    }

    @Test
    void quitAndEndOfInputDoNotCallModel() throws IOException {
        ModelClient unexpected = (messages, tools) -> {
            fail("退出时不应请求模型");
            return new ChatMessage("assistant", "");
        };
        run("quit\n", unexpected);
        run("", unexpected);
    }

    @Test
    void interruptionStopsLoopAndRestoresInterruptFlag() throws IOException {
        try {
            String output = run("你好\n不应继续\n", (messages, tools) -> {
                throw new InterruptedException();
            });
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(output.contains("中断"));
        } finally {
            Thread.interrupted();
        }
    }

    private String run(String input, ModelClient client) throws IOException {
        StringWriter output = new StringWriter();
        new ChatLoop(client).run(new BufferedReader(new StringReader(input)), new PrintWriter(output, true));
        return output.toString();
    }
}
