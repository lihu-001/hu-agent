package dev.huagent.command;

import dev.huagent.model.ChatMessage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CommandRegistryTest {
    private final List<String> warnings = new ArrayList<>();
    private final CommandContext context = new CommandContext(List.of(new ChatMessage("system", "系统")), List.of(),
            new PrintWriter(new StringWriter()));

    @Test
    void helpComesFirstAndListsUsageAliasesAndPromptMarker() throws Exception {
        CommandRegistry registry = new CommandRegistry(List.of(
                SlashCommand.local("exit", "退出", (a, c) -> CommandResult.exit()).withAliases("quit"),
                SlashCommand.prompt("review", "/review [范围]", "审查", (a, c) -> CommandResult.print(a))),
                warnings::add);
        assertEquals(List.of("help", "exit", "review"),
                registry.commands().stream().map(SlashCommand::name).toList());
        CommandResult help = registry.dispatch("/help", context);
        assertEquals(CommandResult.Kind.PRINT, help.kind());
        assertTrue(help.text().contains("\n  /help — 列出全部斜杠命令"), help.text());
        assertTrue(help.text().contains("\n  /exit（/quit） — 退出"), help.text());
        assertTrue(help.text().contains("\n  * /review [范围] — 审查"), help.text());
        assertEquals(CommandResult.Kind.EXIT, registry.dispatch("/quit", context).kind(), "别名");
        assertTrue(warnings.isEmpty());
    }

    @Test
    void nonCommandTextReturnsNullSoItIsSentAsPrompt() throws Exception {
        CommandRegistry registry = new CommandRegistry(List.of(), warnings::add);
        assertNull(registry.dispatch("你好", context));
        assertNull(registry.dispatch("/usr/bin/env python", context));
    }

    @Test
    void unknownCommandIsNotSentAndSuggestsSimilarNames() throws Exception {
        CommandRegistry registry = new CommandRegistry(List.of(
                SlashCommand.local("skills", "技能", (a, c) -> CommandResult.print("")),
                SlashCommand.prompt("goal", "/goal", "目标", (a, c) -> CommandResult.print(""))), warnings::add);
        CommandResult skill = registry.dispatch("/skill", context);
        assertEquals(CommandResult.Kind.PRINT, skill.kind());
        assertEquals("命令> 未知命令 /skill，未发送给模型。你是不是想输入 /skills？输入 /help 查看全部命令。", skill.text());
        assertTrue(registry.dispatch("/goals", context).text().contains("你是不是想输入 /goal？"));
        assertEquals("命令> 未知命令 /zzz，未发送给模型。输入 /help 查看全部命令。",
                registry.dispatch("/zzz 参数", context).text());
    }

    @Test
    void argumentToNoArgumentCommandIsRejectedWithoutRunningHandler() throws Exception {
        CommandRegistry registry = new CommandRegistry(List.of(SlashCommand.local("status", "状态",
                (a, c) -> fail("带参数时不应执行"))), warnings::add);
        assertEquals("命令> /status 不接受参数，未执行。用法：/status", registry.dispatch("/status now", context).text());
    }

    @Test
    void argumentIsStrippedAndPassedToHandler() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        CommandRegistry registry = new CommandRegistry(List.of(SlashCommand.localWithArgument("compact",
                "/compact [重点]", "压缩", (a, c) -> {
                    seen.set(a);
                    return CommandResult.print("ok");
                })), warnings::add);
        registry.dispatch("/compact   文件名  ", context);
        assertEquals("文件名", seen.get());
        registry.dispatch("/compact", context);
        assertEquals("", seen.get());
    }

    @Test
    void duplicateNamesOrAliasesAreSkippedWithWarningFirstWins() throws Exception {
        CommandRegistry registry = new CommandRegistry(List.of(
                SlashCommand.local("exit", "退出", (a, c) -> CommandResult.exit()).withAliases("quit"),
                SlashCommand.prompt("help", "/help", "同名技能", (a, c) -> fail("内置 /help 优先")),
                SlashCommand.prompt("quit", "/quit", "与别名同名", (a, c) -> fail("别名优先"))), warnings::add);
        assertEquals(List.of("命令> /help 与已有命令重名，/help 未注册。", "命令> /quit 与已有命令重名，/quit 未注册。"),
                warnings);
        assertEquals(2, registry.commands().size());
        assertTrue(registry.dispatch("/help", context).text().startsWith("命令> 本地命令"));
    }

    @Test
    void handlerIoErrorPropagatesToLoop() {
        CommandRegistry registry = new CommandRegistry(List.of(SlashCommand.local("broken", "坏",
                (a, c) -> {
                    throw new IOException("磁盘错误");
                })), warnings::add);
        assertThrows(IOException.class, () -> registry.dispatch("/broken", context));
    }

    @Test
    void invalidCommandNamesAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> SlashCommand.local("Bad", "x", (a, c) -> CommandResult.exit()));
        assertThrows(IllegalArgumentException.class,
                () -> SlashCommand.local("ok", "x", (a, c) -> CommandResult.exit()).withAliases("no way"));
    }

    @Test
    void contextRequiresSystemPromptFirstAndTracksReplacement() {
        assertThrows(IllegalArgumentException.class, () -> new CommandContext(List.of(), List.of(),
                new PrintWriter(new StringWriter())));
        CommandContext ctx = new CommandContext(List.of(new ChatMessage("system", "s"), new ChatMessage("user", "u")),
                List.of(), new PrintWriter(new StringWriter()));
        assertFalse(ctx.historyReplaced());
        assertThrows(UnsupportedOperationException.class, () -> ctx.history().add(new ChatMessage("user", "x")));
        assertThrows(IllegalArgumentException.class, () -> ctx.replaceHistory(List.of(new ChatMessage("user", "x"))));
        ctx.replaceHistory(List.of(new ChatMessage("system", "s")));
        assertTrue(ctx.historyReplaced());
        assertEquals(1, ctx.history().size());
    }
}
