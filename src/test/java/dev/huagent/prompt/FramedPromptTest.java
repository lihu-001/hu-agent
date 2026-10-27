package dev.huagent.prompt;

import dev.huagent.harness.TurnEvents;
import dev.huagent.hook.AgentHooks;
import dev.huagent.loop.ChatLoop;
import dev.huagent.model.ChatMessage;
import dev.huagent.tool.ToolRegistry;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class FramedPromptTest {
    private static final String RULE = "─".repeat(11);

    @Test
    void beginDrawsBlankLineRuleAndMarker() {
        String text = render(out -> new FramedPrompt("❯ ", '─', () -> 12).begin(out));
        assertEquals(System.lineSeparator() + RULE + System.lineSeparator() + "❯ ", text);
    }

    @Test
    void endClosesTypedInputWithRuleButOnlyEndsLineForWakeAndEof() {
        FramedPrompt prompt = new FramedPrompt("❯ ", '─', () -> 12);
        assertEquals(RULE + System.lineSeparator(), render(out -> prompt.end(out, "你好")));
        assertEquals(System.lineSeparator(), render(out -> prompt.end(out, TurnEvents.WAKE_LINE)));
        assertEquals(System.lineSeparator(), render(out -> prompt.end(out, null)));
    }

    @Test
    void ruleFollowsCurrentWidthWithMinimum() {
        int[] columns = {30};
        FramedPrompt prompt = new FramedPrompt("> ", '-', () -> columns[0]);
        assertEquals("-".repeat(29), prompt.rule());
        columns[0] = 5;
        assertEquals("-".repeat(FramedPrompt.MIN_RULE), prompt.rule());
    }

    @Test
    void terminalCharsetDecidesUnicodeOrAscii() {
        assertTrue(render(out -> FramedPrompt.forTerminal(StandardCharsets.UTF_8, () -> 12).begin(out))
                .endsWith(RULE + System.lineSeparator() + "❯ "));
        // GBK 编码不了 ❯：整体改用 ASCII，分隔线也不用会占两列的 ─。
        assertTrue(render(out -> FramedPrompt.forTerminal(Charset.forName("GBK"), () -> 12).begin(out))
                .endsWith("-".repeat(11) + System.lineSeparator() + "> "));
    }

    @Test
    void chatLoopFramesEachInput() throws Exception {
        StringWriter buffer = new StringWriter();
        new ChatLoop((messages, tools) -> new ChatMessage("assistant", "好的"), new ToolRegistry(List.of()), 3,
                "系统", AgentHooks.empty(), null, null, null, null, null, null, null, false,
                new FramedPrompt("❯ ", '─', () -> 12))
                .run(new BufferedReader(new StringReader("你好\nexit\n")), new PrintWriter(buffer));
        // 测试输入不回显，下分隔线紧跟在提示符后面；真实终端里它在用户输入的下一行。
        assertEquals(List.of("", RULE, "❯ " + RULE, "助手> 好的", "", RULE, "❯ " + RULE, "对话已结束。"),
                buffer.toString().lines().toList());
    }

    private static String render(Consumer<PrintWriter> action) {
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        action.accept(output);
        output.flush();
        return buffer.toString();
    }
}
