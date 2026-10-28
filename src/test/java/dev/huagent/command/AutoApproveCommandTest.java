package dev.huagent.command;

import dev.huagent.model.ChatMessage;
import dev.huagent.permission.AutoApprove;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoApproveCommandTest {
    private final List<String> warnings = new ArrayList<>();
    private final CommandContext context = new CommandContext(List.of(new ChatMessage("system", "系统")), List.of(),
            new PrintWriter(new StringWriter()));

    @Test
    void togglesWithoutArgumentAndSetsWithOnOff() throws Exception {
        AutoApprove autoApprove = new AutoApprove(false);
        CommandRegistry registry = new CommandRegistry(List.of(SessionCommands.autoApprove(autoApprove)), warnings::add);

        assertTrue(text(registry, "/auto").startsWith("审批> 已开启"));
        assertTrue(autoApprove.enabled());
        assertEquals("审批> 已经开启，无需切换。", text(registry, "/auto on"));
        assertTrue(text(registry, "/auto").startsWith("审批> 已关闭"));
        assertFalse(autoApprove.enabled());
        assertEquals("审批> 已经关闭，无需切换。", text(registry, "/auto off"));
        assertTrue(text(registry, "/auto on").startsWith("审批> 已开启"));
        assertTrue(text(registry, "/auto off").startsWith("审批> 已关闭"));

        assertEquals("审批> 用法：/auto [on | off]，不带参数时切换开关。", text(registry, "/auto yes"));
        assertFalse(autoApprove.enabled(), "无效参数不改变开关");
        assertTrue(warnings.isEmpty());
    }

    @Test
    void completionOffersOnAndOff() {
        assertEquals(List.of("on", "off"), SessionCommands.autoApprove(new AutoApprove(false)).choices().get().stream()
                .map(SlashCommand.Choice::value).toList());
    }

    private String text(CommandRegistry registry, String line) throws Exception {
        CommandResult result = registry.dispatch(line, context);
        assertEquals(CommandResult.Kind.PRINT, result.kind());
        return result.text();
    }
}
