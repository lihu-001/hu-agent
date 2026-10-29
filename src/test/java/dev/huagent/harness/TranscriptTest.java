package dev.huagent.harness;

import dev.huagent.hook.AgentHooks;
import dev.huagent.loop.ChatLoop;
import dev.huagent.model.ChatMessage;
import dev.huagent.prompt.FramedPrompt;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.tool.ToolRegistry;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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

    private static void run(Transcript transcript, PrintWriter output) {
        transcript.startTurn();
        transcript.reply(output, "好的");
        transcript.tool(output, READ, "     1\ta");
    }

    private static List<String> render(Consumer<PrintWriter> action) {
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        action.accept(output);
        output.flush();
        return buffer.toString().lines().toList();
    }
}
