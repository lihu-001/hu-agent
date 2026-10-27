package dev.huagent.compact;

import dev.huagent.model.ChatMessage;
import dev.huagent.tool.ToolCall;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MicroCompactTest {
    private static final String LONG = "x".repeat(MicroCompact.MIN_COMPACT_CHARS + 1);

    @Test
    void replacesOlderLongResultsAndKeepsRecentOnes() {
        List<ChatMessage> messages = conversation("read_file", "read_file", "shell", "glob");
        List<ChatMessage> result = MicroCompact.apply(messages, 2);
        List<ChatMessage> tools = result.stream().filter(m -> "tool".equals(m.role())).toList();
        assertTrue(tools.get(0).content().startsWith(MicroCompact.PLACEHOLDER_PREFIX + "较早的 read_file"));
        assertTrue(tools.get(0).content().contains("原 " + LONG.length() + " 字符"));
        assertTrue(tools.get(1).content().startsWith(MicroCompact.PLACEHOLDER_PREFIX));
        assertEquals(LONG, tools.get(2).content());
        assertEquals(LONG, tools.get(3).content());
        // 调用 ID 与消息顺序不变，入参不被修改。
        assertEquals(messages.stream().map(ChatMessage::toolCallId).toList(),
                result.stream().map(ChatMessage::toolCallId).toList());
        assertEquals(LONG, messages.get(3).content());
    }

    @Test
    void keepsShortResultsAndExemptTools() {
        List<ChatMessage> messages = new ArrayList<>(conversation("task", "load_skill", "todo_write", "shell"));
        messages.add(ChatMessage.assistant(null, List.of(new ToolCall("short", "shell", "{}"))));
        messages.add(ChatMessage.tool("short", "ok"));
        List<String> contents = MicroCompact.apply(messages, 0).stream()
                .filter(m -> "tool".equals(m.role())).map(ChatMessage::content).toList();
        assertEquals(List.of(LONG, LONG, LONG), contents.subList(0, 3));
        assertTrue(contents.get(3).startsWith(MicroCompact.PLACEHOLDER_PREFIX));
        assertEquals("ok", contents.get(4));
    }

    @Test
    void returnsSameInstancesWhenNothingToCompact() {
        List<ChatMessage> messages = conversation("shell", "shell");
        List<ChatMessage> result = MicroCompact.apply(messages, 3);
        for (int i = 0; i < messages.size(); i++) {
            assertSame(messages.get(i), result.get(i));
        }
    }

    @Test
    void sizeCountsContentAndToolArguments() {
        List<ChatMessage> messages = List.of(new ChatMessage("user", "abc"),
                ChatMessage.assistant(null, List.of(new ToolCall("1", "shell", "{\"a\":1}"))),
                ChatMessage.tool("1", "12345"));
        assertEquals(3 + 5 + 7 + 5, MicroCompact.size(messages));
    }

    private static List<ChatMessage> conversation(String... tools) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "s"));
        messages.add(new ChatMessage("user", "u"));
        for (int i = 0; i < tools.length; i++) {
            messages.add(ChatMessage.assistant(null, List.of(new ToolCall("c" + i, tools[i], "{}"))));
            messages.add(ChatMessage.tool("c" + i, LONG));
        }
        return messages;
    }
}
