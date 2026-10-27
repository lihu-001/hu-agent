package dev.huagent.compact;

import dev.huagent.model.ChatMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CompactToolTest {
    @TempDir
    Path root;

    @Test
    void registersRequestWithOptionalFocus() {
        ContextCompactor compactor = compactor();
        CompactTool tool = new CompactTool(compactor);
        assertEquals("compact", tool.definition().name());
        assertTrue(tool.execute("{}").startsWith("已安排压缩"));
        assertTrue(compactor.pending());
        tool.execute("{\"focus\":\"路径\"}");
        assertTrue(compactor.pending());
    }

    @Test
    void rejectsInvalidArgumentsWithoutRegistering() {
        ContextCompactor compactor = compactor();
        CompactTool tool = new CompactTool(compactor);
        for (String args : new String[]{"not json", "{\"x\":1}", "{\"focus\":1}",
                "{\"focus\":\"" + "长".repeat(CompactTool.MAX_FOCUS_CHARS + 1) + "\"}"}) {
            assertThrows(IllegalArgumentException.class, () -> tool.execute(args), args);
        }
        assertFalse(compactor.pending());
    }

    private ContextCompactor compactor() {
        return new ContextCompactor((m, t) -> new ChatMessage("assistant", "s"), new TranscriptStore(root),
                root, 1000, 3, null);
    }
}
