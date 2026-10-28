package dev.huagent.prompt;

import dev.huagent.command.CommandRegistry;
import dev.huagent.command.CommandResult;
import dev.huagent.command.SlashCommand;
import dev.huagent.harness.TurnEvents;
import dev.huagent.hook.AgentHooks;
import dev.huagent.loop.ChatLoop;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.ToolDefinition;
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
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

class FramedPromptTest {
    private static final String RULE = "─".repeat(11);

    @Test
    void beginDrawsBlankLineRuleAndMarker() {
        String text = render(out -> new FramedPrompt("❯ ", '─', () -> 12).begin(out));
        assertEquals(System.lineSeparator() + RULE + System.lineSeparator() + "❯ ", text);
    }

    @Test
    void endDrawsNoRuleOnlyLineBreak() {
        FramedPrompt prompt = new FramedPrompt("❯ ", '─', () -> 12);
        assertEquals(System.lineSeparator(), render(out -> prompt.end(out, "你好")));
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
        assertTrue(render(out -> FramedPrompt.forTerminal(StandardCharsets.UTF_8, () -> 12, null, 0).begin(out))
                .endsWith(RULE + System.lineSeparator() + "❯ "));
        // GBK 编码不了 ❯：整体改用 ASCII，分隔线也不用会占两列的 ─。
        assertTrue(render(out -> FramedPrompt.forTerminal(Charset.forName("GBK"), () -> 12, null, 0).begin(out))
                .endsWith("-".repeat(11) + System.lineSeparator() + "> "));
    }

    @Test
    void ruleShowsModelThenContextAndFillsWidth() {
        FramedPrompt prompt = new FramedPrompt("❯ ", '─', () -> 60, " glm-4.6 ", 1_000_000);
        // 还没有用量：只显示最大值。中文按两列计，前缀共 34 列，补 25 个 ─ 到 59 列。
        assertEquals("── glm-4.6 ── 上下文最大 1M token " + "─".repeat(25), prompt.rule());
        prompt.context(1766);
        assertEquals("── glm-4.6 ── 上下文剩余 998.2K / 最大 1M token " + "─".repeat(11), prompt.rule());
        assertEquals(59, FramedPrompt.displayWidth(prompt.rule()));
        // 配置的最大值比实际用量小时剩余显示为 0。
        prompt.context(2_000_000);
        assertTrue(prompt.rule().contains("上下文剩余 0 / 最大 1M token"), prompt.rule());
        prompt.context(-1);
        assertTrue(prompt.rule().startsWith("── glm-4.6 ── 上下文最大 1M token "), prompt.rule());
    }

    @Test
    void ruleShowsOnlyWhatIsConfigured() {
        FramedPrompt contextOnly = new FramedPrompt("> ", '-', () -> 50, null, 128_000);
        contextOnly.context(28_000);
        assertEquals("-- 上下文剩余 100K / 最大 128K token " + "-".repeat(12), contextOnly.rule());
        FramedPrompt modelOnly = new FramedPrompt("❯ ", '─', () -> 60, "glm-4.6", 0);
        modelOnly.context(1766);
        assertEquals("── glm-4.6 " + "─".repeat(48), modelOnly.rule());
    }

    @Test
    void narrowTerminalFallsBackToPlainRule() {
        FramedPrompt prompt = new FramedPrompt("❯ ", '─', () -> 20, "glm-4.6", 1_000_000);
        prompt.context(0);
        assertEquals("─".repeat(19), prompt.rule());
    }

    @Test
    void abbreviateUsesKAndM() {
        assertEquals("999", FramedPrompt.abbreviate(999));
        assertEquals("1K", FramedPrompt.abbreviate(1000));
        assertEquals("38.2K", FramedPrompt.abbreviate(38234));
        assertEquals("999.9K", FramedPrompt.abbreviate(999_949));
        assertEquals("1M", FramedPrompt.abbreviate(999_950));
        assertEquals("1.2M", FramedPrompt.abbreviate(1_234_567));
    }

    @Test
    void chatLoopReportsUsageAndForgetsItWhenUnknownOrHistoryReplaced() throws Exception {
        int[] reported = {1766, -1, 2000};
        int[] calls = {0};
        ModelClient client = new ModelClient() {
            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                return new ChatMessage("assistant", "好的");
            }

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools, IntConsumer tokens) {
                int value = reported[calls[0]++];
                if (value >= 0) {
                    tokens.accept(value);
                }
                return complete(messages, tools);
            }
        };
        CommandRegistry commands = new CommandRegistry(List.of(SlashCommand.local("reset", "替换历史",
                (argument, context) -> {
                    context.replaceHistory(List.of(context.history().get(0)));
                    return CommandResult.print("已替换");
                })), warning -> fail(warning));
        StringWriter buffer = new StringWriter();
        new ChatLoop(client, new ToolRegistry(List.of()), 3, "系统", AgentHooks.empty(), null, null, null, null,
                null, null, commands, false, new FramedPrompt("❯ ", '─', () -> 60, "m", 1_000_000))
                .run(new BufferedReader(new StringReader("一\n二\n三\n/reset\nexit\n")), new PrintWriter(buffer));
        String unknown = "── m ── 上下文最大 1M token ";
        List<String> rules = buffer.toString().lines().filter(line -> line.startsWith("──"))
                .map(line -> line.replaceAll("─+$", "")).toList();
        // 开始时未知；第二次响应没有 usage，不沿用上一次；/reset 替换历史后回到未知。
        assertEquals(List.of(unknown, "── m ── 上下文剩余 998.2K / 最大 1M token ", unknown,
                "── m ── 上下文剩余 998K / 最大 1M token ", unknown), rules);
    }

    @Test
    void chatLoopFramesEachInput() throws Exception {
        StringWriter buffer = new StringWriter();
        new ChatLoop((messages, tools) -> new ChatMessage("assistant", "好的"), new ToolRegistry(List.of()), 3,
                "系统", AgentHooks.empty(), null, null, null, null, null, null, null, false,
                new FramedPrompt("❯ ", '─', () -> 12))
                .run(new BufferedReader(new StringReader("你好\nexit\n")), new PrintWriter(buffer));
        // 只有输入上方有分隔线；测试输入不回显，end 的换行直接结束提示符这一行，真实终端里它是输入下的空行。
        assertEquals(List.of("", RULE, "❯ ", "助手> 好的", "", RULE, "❯ ", "对话已结束。"),
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
