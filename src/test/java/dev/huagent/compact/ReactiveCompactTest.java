package dev.huagent.compact;

import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** S15：force 模式不看阈值，用于上下文超长后的响应式压缩；没有可总结内容时明确返回未压缩。 */
class ReactiveCompactTest {
    private static final ModelClient SUMMARY = (messages, tools) ->
            ChatMessage.assistant("摘要：之前的对话在测试。", List.of());

    private static PrintWriter quietOut() {
        return new PrintWriter(new StringWriter(), true);
    }

    @Test
    void forceCompactsBelowThreshold(@TempDir Path dir) throws Exception {
        ContextCompactor compactor = new ContextCompactor(SUMMARY, new TranscriptStore(dir), dir,
                100_000, 3, null);
        List<ChatMessage> history = List.of(
                new ChatMessage("system", "sys"),
                new ChatMessage("user", "早期输入"),
                ChatMessage.assistant("中间回复", List.of(new ToolCall("c1", "echo", "{}"))),
                ChatMessage.tool("c1", "退出码：0\nok"));

        PrintWriter out = quietOut();
        ContextCompactor.Prepared prepared = compactor.beforeRequest(history, out, true);

        assertTrue(prepared.summarized());
        assertEquals(2, prepared.history().size());
        assertEquals("system", prepared.history().get(0).role());
        assertTrue(prepared.history().get(1).content().startsWith(ContextCompactor.SUMMARY_MARKER));
        assertTrue(prepared.history().get(1).content().contains("摘要：之前的对话在测试。"));
    }

    @Test
    void forceWithoutSummarizableHistoryReturnsUnsummarized(@TempDir Path dir) throws Exception {
        ContextCompactor compactor = new ContextCompactor(SUMMARY, new TranscriptStore(dir), dir,
                100_000, 3, null);
        List<ChatMessage> history = List.of(new ChatMessage("system", "sys"), new ChatMessage("user", "只有输入"));

        PrintWriter out = quietOut();
        ContextCompactor.Prepared prepared = compactor.beforeRequest(history, out, true);

        assertFalse(prepared.summarized());
        assertSame(history, prepared.history());
    }

    @Test
    void normalThresholdBehaviorUnchangedByForceOverload(@TempDir Path dir) throws Exception {
        // 阈值 1：任何对话都会触发自动压缩；两参重载保持 S08 行为。
        ContextCompactor compactor = new ContextCompactor(SUMMARY, new TranscriptStore(dir), dir, 1, 3, null);
        List<ChatMessage> history = List.of(
                new ChatMessage("system", "sys"),
                new ChatMessage("user", "输入"),
                ChatMessage.assistant("回复", List.of()));

        PrintWriter out = quietOut();
        ContextCompactor.Prepared prepared = compactor.beforeRequest(history, out);

        assertTrue(prepared.summarized());
    }
}
