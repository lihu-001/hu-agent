package dev.huagent.harness;

import dev.huagent.hook.AgentHooks;
import dev.huagent.loop.ChatLoop;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.model.ModelUnavailableException;
import dev.huagent.prompt.FramedPrompt;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.tool.ToolRegistry;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptTest {
    private static final ToolCall READ = new ToolCall("r", "read_file", "{\"path\":\"src/A.java\"}");
    private static final ToolCall SHELL = new ToolCall("s", "shell", "{\"command\":\"mvn test\"}");

    @Test
    void plainKeepsOneLinePerReplyAndToolWithoutGaps() {
        Transcript transcript = Transcript.plain();
        assertEquals(List.of("助手> 先看看", "工具> read_file src/A.java · 2 行", "助手> 看完了"), render(output -> {
            transcript.startTurn();
            transcript.reply(output, "先看看");
            transcript.beforeTool(output);
            transcript.tool(output, READ, "     1\ta\n     2\tb");
            transcript.reply(output, "看完了");
        }));
    }

    @Test
    void headlessHidesOnlyTheFinalReply() {
        Transcript transcript = Transcript.headless();
        assertEquals(List.of("助手> 先看看", "工具> read_file src/A.java · 2 行"), render(output -> {
            transcript.startTurn();
            transcript.reply(output, "先看看", false);
            transcript.beforeTool(output);
            transcript.tool(output, READ, "     1\ta\n     2\tb");
            transcript.reply(output, "看完了", true);
        }));
        // 其他样式照常显示最终回答。
        assertEquals(List.of("助手> 看完了"), render(output -> Transcript.plain().reply(output, "看完了", true)));
    }

    @Test
    void toolNameFromModelIsEscapedBeforeReachingTerminal() {
        ToolCall forged = new ToolCall("x", "x\u001b]0;pwned\u0007", "{}");
        for (Transcript transcript : List.of(Transcript.plain(), Transcript.marked("●", "⎿", true))) {
            List<String> lines = render(output -> transcript.tool(output, forged, "工具错误：未知工具"));
            assertFalse(String.join("\n", lines).contains("\u001b]"), lines.toString());
            assertTrue(lines.get(0).contains("x\\u001b]0;pwned\\u0007"), lines.toString());
        }
    }

    @Test
    void markedSeparatesRepliesAndToolsIntoBlocks() {
        Transcript transcript = Transcript.marked("●", "⎿", false);
        assertEquals(List.of(
                "● 先看看", "  第二行", "", "  第四行",
                "",
                "● read_file src/A.java", "  ⎿  2 行",
                "",
                "● shell", "  ⎿  退出码 0，无输出",
                "",
                "● 看完了"), render(output -> {
            transcript.startTurn();
            transcript.reply(output, "\n先看看\n第二行\n\n第四行\n");
            transcript.tool(output, READ, "     1\ta\n     2\tb");
            transcript.tool(output, SHELL, "退出码：0\n");
            transcript.reply(output, "看完了");
        }));
    }

    @Test
    void confirmationPrintedDuringToolBelongsToItsBlock() {
        Transcript transcript = Transcript.marked("●", "⎿", false);
        assertEquals(List.of("● 先跑测试", "", "【权限确认】shell：mvn test", "● shell", "  ⎿  退出码 0，无输出"),
                render(output -> {
                    transcript.startTurn();
                    transcript.reply(output, "先跑测试");
                    transcript.beforeTool(output);
                    output.println("【权限确认】shell：mvn test");
                    transcript.tool(output, SHELL, "退出码：0\n");
                }));
    }

    @Test
    void attachedImagesFormTheirOwnBlock() {
        Transcript marked = Transcript.marked("●", "⎿", false);
        assertEquals(List.of("  ⎿  已附加图片 a.png（1386×272）", "  ⎿  未附加图片 b.png：不是 PNG、JPEG、GIF 或 WebP 图片",
                "", "● 看到了"), render(output -> {
            marked.startTurn();
            marked.images(output, List.of("a.png（1386×272）"), List.of("b.png：不是 PNG、JPEG、GIF 或 WebP 图片"));
            marked.reply(output, "看到了");
        }));
        assertEquals(List.of("图片> 已附加 a.png（3×2）", "图片> 未附加 b\\u001b.png：读取失败"),
                render(output -> Transcript.plain().images(output, List.of("a.png（3×2）"),
                        List.of("b\u001b.png：读取失败"))));
    }

    @Test
    void firstBlockOfEachTurnHasNoGap() {
        Transcript transcript = Transcript.marked("●", "⎿", false);
        assertEquals(List.of("● 一", "● 二"), render(output -> {
            transcript.startTurn();
            transcript.reply(output, "一");
            transcript.startTurn();
            transcript.beforeTool(output);
            transcript.startTurn();
            transcript.reply(output, "二");
        }));
    }

    @Test
    void colorMarksToolsAndFailures() {
        Transcript transcript = Transcript.marked("●", "⎿", true);
        assertEquals(List.of("● 好的", "",
                "\u001b[32m●\u001b[0m \u001b[1mread_file\u001b[0m src/A.java", "  \u001b[2m⎿  1 行\u001b[0m", "",
                "\u001b[31m●\u001b[0m \u001b[1mshell\u001b[0m", "  \u001b[31m⎿  权限拒绝：用户未批准本次调用。\u001b[0m"),
                render(output -> {
                    transcript.startTurn();
                    transcript.reply(output, "好的");
                    transcript.tool(output, READ, "     1\ta");
                    transcript.tool(output, SHELL, "权限拒绝：用户未批准本次调用。");
                }));
    }

    @Test
    void terminalWithoutUnicodeSymbolsFallsBackToAscii() {
        assertEquals(List.of("* 好的", "", "* read_file src/A.java", "  -  1 行"),
                render(output -> run(Transcript.forTerminal(Charset.forName("GBK"), false), output)));
        assertEquals(List.of("● 好的", "", "● read_file src/A.java", "  ⎿  1 行"),
                render(output -> run(Transcript.forTerminal(StandardCharsets.UTF_8, false), output)));
    }

    @Test
    void chatLoopShowsIntentToolsAndAnswerAsSeparateBlocks() throws Exception {
        List<String> executed = new ArrayList<>();
        AgentTool read = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("read_file", "读文件", new JSONObject());
            }

            @Override
            public String execute(String arguments) {
                executed.add(arguments);
                return "     1\ta";
            }
        };
        List<ChatMessage> answers = List.of(
                ChatMessage.assistant("先读两个文件", List.of(READ, new ToolCall("r2", "read_file", "{\"path\":\"B\"}"))),
                new ChatMessage("assistant", "读完了"));
        StringWriter buffer = new StringWriter();
        new ChatLoop((messages, tools) -> answers.get(messages.size() == 2 ? 0 : 1), new ToolRegistry(List.of(read)),
                5, "系统", AgentHooks.empty(), null, null, null, null, null, null, null, false,
                new FramedPrompt("❯ ", '─', () -> 12), null, Transcript.marked("●", "⎿", false))
                .run(new BufferedReader(new StringReader("读一下\nexit\n")), new PrintWriter(buffer));
        assertEquals(2, executed.size());
        String rule = "─".repeat(11);
        assertEquals(List.of("", rule, "❯ ",
                "● 先读两个文件", "",
                "● read_file src/A.java", "  ⎿  1 行", "",
                "● read_file B", "  ⎿  1 行", "",
                "● 读完了",
                "", rule, "❯ ", "对话已结束。"), buffer.toString().lines().toList());
    }

    @Test
    void streamedReplyLooksExactlyLikeWholeReply() {
        List<String> texts = List.of("\n先看看\n第二行\n\n第四行\n", "一行", "a\r\nb\r\n\r\n\r\nc  \n", "  前导空白\n\n\n",
                "  代码缩进\n    保留\u2028下一行\r结束", "末尾有换行\n");
        for (Transcript style : List.of(Transcript.marked("●", "⎿", false), Transcript.plain())) {
            for (String text : texts) {
                String whole = renderRaw(output -> {
                    style.startTurn();
                    style.reply(output, text);
                });
                for (int size : List.of(1, 2, 3, text.length())) {
                    String streamed = renderRaw(output -> {
                        style.startTurn();
                        Transcript.ReplyStream stream = style.replyStream();
                        for (int i = 0; i < text.length(); i += size) {
                            stream.append(output, text.substring(i, Math.min(text.length(), i + size)));
                        }
                        stream.finish(output);
                        assertTrue(stream.received());
                    });
                    assertEquals(whole, streamed, "每段 " + size + " 个字符：" + text);
                }
            }
        }
    }

    @Test
    void replyStreamWritesOnlyCompleteLinesUntilFinished() {
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        Transcript transcript = Transcript.marked("●", "⎿", false);
        transcript.startTurn();
        Transcript.ReplyStream stream = transcript.replyStream();
        assertFalse(stream.received());

        stream.append(output, "第一行\n第二");
        output.flush();
        assertEquals(List.of("● 第一行"), buffer.toString().lines().toList());

        stream.append(output, "行\n\n");
        stream.finish(output);
        output.flush();
        assertEquals(List.of("● 第一行", "  第二行"), buffer.toString().lines().toList(), "末尾的空行不显示");
        // 之后的工具调用照常与回复隔开一行。
        transcript.tool(output, READ, "     1\ta");
        output.flush();
        assertEquals(List.of("● 第一行", "  第二行", "", "● read_file src/A.java", "  ⎿  1 行"),
                buffer.toString().lines().toList());
    }

    @Test
    void headlessDoesNotStreamBecauseTheFinalAnswerGoesToStdout() {
        assertNull(Transcript.headless().replyStream());
    }

    @Test
    void chatLoopShowsStreamedLinesWhileTheRequestIsStillRunning() throws Exception {
        StringWriter buffer = new StringWriter();
        List<String> seenDuringRequest = new ArrayList<>();
        ModelClient streaming = new ModelClient() {
            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                throw new AssertionError("ChatLoop 应走带 text 的请求");
            }

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools,
                                        IntConsumer contextTokens, Consumer<String> text) {
                text.accept("先读");
                text.accept("文件\n第二");
                seenDuringRequest.add(buffer.toString());
                text.accept("行");
                return ChatMessage.assistant("先读文件\n第二行", List.of());
            }
        };
        new ChatLoop(streaming, new ToolRegistry(List.of()), 5, "系统", AgentHooks.empty(), null, null, null, null,
                null, null, null, false, new FramedPrompt("❯ ", '─', () -> 12), null, Transcript.marked("●", "⎿", false))
                .run(new BufferedReader(new StringReader("你好\nexit\n")), new PrintWriter(buffer));

        assertTrue(seenDuringRequest.getFirst().contains("● 先读文件"), seenDuringRequest.toString());
        assertFalse(seenDuringRequest.getFirst().contains("第二"), "不足一行的部分等换行或结束");
        String rule = "─".repeat(11);
        assertEquals(List.of("", rule, "❯ ", "● 先读文件", "  第二行", "", rule, "❯ ", "对话已结束。"),
                buffer.toString().lines().toList(), "流式显示过的回复不再整段显示");
    }

    @Test
    void retriedRequestShowsThePartialLineBeforeTheRetryNotice() throws Exception {
        StringWriter buffer = new StringWriter();
        int[] calls = {0};
        ModelClient flaky = new ModelClient() {
            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                throw new AssertionError("ChatLoop 应走带 text 的请求");
            }

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools,
                                        IntConsumer contextTokens, Consumer<String> text) throws IOException {
                if (calls[0]++ == 0) {
                    text.accept("说到一");
                    throw new ModelUnavailableException("模型接口的流式响应中断", null);
                }
                text.accept("完整回答");
                return ChatMessage.assistant("完整回答", List.of());
            }
        };
        new ChatLoop(flaky, new ToolRegistry(List.of()), 5, "系统", AgentHooks.empty(), null, null, null, null,
                new ModelRetryPolicy(1, 0, millis -> { }), null, null, false, new FramedPrompt("❯ ", '─', () -> 12), null,
                Transcript.marked("●", "⎿", false))
                .run(new BufferedReader(new StringReader("你好\nexit\n")), new PrintWriter(buffer));

        List<String> lines = buffer.toString().lines().toList();
        int partial = lines.indexOf("● 说到一");
        int notice = lines.indexOf("模型请求> 模型接口的流式响应中断，等待 0 秒后第 1 次重试（最多 1 次）...");
        int answer = lines.indexOf("● 完整回答");
        assertTrue(partial >= 0 && partial < notice && notice < answer, lines.toString());
        assertEquals("", lines.get(answer - 1), "重试的回答另起一段");
    }

    private static String renderRaw(Consumer<PrintWriter> action) {
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        action.accept(output);
        output.flush();
        return buffer.toString();
    }

    private static void run(Transcript transcript, PrintWriter output) {
        transcript.startTurn();
        transcript.reply(output, "好的");
        transcript.tool(output, READ, "     1\ta");
    }

    @Test
    void commandOutputIsIndentedAsAWholeOnlyWhenMarked() {
        String text = "工具> 共 2 个：\n  shell — 运行命令\n\n  grep — 搜索";
        assertEquals(List.of("  工具> 共 2 个：", "    shell — 运行命令", "", "    grep — 搜索"),
                render(output -> Transcript.marked("●", "⎿", false).notice(output, text)));
        assertEquals(List.of("工具> 共 2 个：", "  shell — 运行命令", "", "  grep — 搜索"),
                render(output -> Transcript.plain().notice(output, text)));
    }

    @Test
    void onlyMarkedHasAMargin() {
        assertNull(Transcript.plain().margin());
        // 段首与结果行的符号跟着终端字符集走：GBK 控制台的 * 段首同样留在最左列。
        assertEquals("* 回复\n  提示\n",
                Transcript.forTerminal(Charset.forName("GBK"), false).margin().apply("* 回复\n提示\n", 80));
    }

    private static List<String> render(Consumer<PrintWriter> action) {
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        action.accept(output);
        output.flush();
        return buffer.toString().lines().toList();
    }
}
