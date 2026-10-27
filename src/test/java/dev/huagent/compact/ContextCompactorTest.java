package dev.huagent.compact;

import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.tool.ToolCall;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompactorTest {
    @TempDir
    Path root;
    private final StringWriter buffer = new StringWriter();
    private final PrintWriter output = new PrintWriter(buffer);

    @Test
    void belowThresholdOnlyMicroCompactsTheRequest() throws Exception {
        ContextCompactor compactor = compactor((m, t) -> fail("不应请求总结"), 100000, 1, null);
        List<ChatMessage> history = toolHistory(3);
        ContextCompactor.Prepared prepared = compactor.beforeRequest(history, output);
        assertFalse(prepared.summarized());
        assertSame(history, prepared.history(), "内存历史保留原文");
        assertEquals(2, prepared.sent().stream().filter(m -> m.content() != null
                && m.content().startsWith(MicroCompact.PLACEHOLDER_PREFIX)).count());
        assertTrue(buffer.toString().contains("微压缩：本次请求中 2 个"), buffer.toString());
        // 同样的替换不重复打印。
        compactor.beforeRequest(history, output);
        assertEquals(1, buffer.toString().split("微压缩").length - 1);
    }

    @Test
    void aboveThresholdSavesTranscriptAndReplacesHistoryWithSummary() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        ContextCompactor compactor = compactor((messages, tools) -> {
            requests.add(messages);
            assertTrue(tools.isEmpty(), "总结请求不带工具");
            return new ChatMessage("assistant", "  摘要：读了两个文件  ");
        }, 100, 3, () -> "任务清单（0/1 完成）：\n[ ] 写测试");
        List<ChatMessage> history = new ArrayList<>(toolHistory(2));
        history.add(ChatMessage.assistant("读完了", List.of()));
        history.add(new ChatMessage("user", "继续下一步"));
        ContextCompactor.Prepared prepared = compactor.beforeRequest(history, output);

        assertTrue(prepared.summarized());
        assertEquals(List.of("system", "user"), prepared.history().stream().map(ChatMessage::role).toList());
        assertSame(prepared.history(), prepared.sent());
        assertEquals("系统", prepared.history().get(0).content());
        String summary = prepared.history().get(1).content();
        assertTrue(summary.startsWith(ContextCompactor.SUMMARY_MARKER), summary);
        assertTrue(summary.contains("摘要：\n摘要：读了两个文件\n"), summary);
        assertTrue(summary.contains("[ ] 写测试"), "程序状态原样附加");
        assertTrue(summary.endsWith("用户最新输入（原文）：\n继续下一步"), summary);
        assertTrue(summary.contains(".transcripts/transcript-"), summary);

        // 总结请求：总结提示词 + 渲染后的对话，不含本轮未回复的输入和原系统提示词。
        String rendered = requests.get(0).get(1).content();
        assertTrue(rendered.contains("[assistant 调用 read_file]"), rendered);
        assertTrue(rendered.contains("[assistant] 读完了"), rendered);
        assertFalse(rendered.contains("继续下一步"));
        assertFalse(rendered.contains("[system]"));

        // 完整记录保存原文，每行一条消息。
        Path transcript;
        try (var files = Files.list(root.resolve(TranscriptStore.DIRECTORY_NAME))) {
            transcript = files.findFirst().orElseThrow();
        }
        List<String> lines = Files.readAllLines(transcript, StandardCharsets.UTF_8);
        assertEquals(history.size(), lines.size());
        assertEquals("c0", JSON.parseObject(lines.get(2)).getJSONArray("tool_calls").getJSONObject(0).getString("id"));
        assertEquals("R".repeat(300), JSON.parseObject(lines.get(3)).getString("content"));
        assertEquals("c0", JSON.parseObject(lines.get(3)).getString("tool_call_id"));
    }

    @Test
    void manualRequestCompactsOnceWithFocusEvenBelowThreshold() throws Exception {
        List<String> prompts = new ArrayList<>();
        ContextCompactor compactor = compactor((messages, tools) -> {
            prompts.add(messages.get(0).content());
            return new ChatMessage("assistant", "摘要");
        }, 100000, 3, null);
        compactor.requestCompaction("  保留文件路径 ");
        assertTrue(compactor.pending());
        ContextCompactor.Prepared prepared = compactor.beforeRequest(toolHistory(1), output);
        assertTrue(prepared.summarized());
        assertFalse(compactor.pending());
        assertTrue(prompts.get(0).endsWith("本次压缩请特别保留：保留文件路径"), prompts.get(0));
        // 本轮工具执行中途压缩，本轮的用户输入仍原样保留。
        assertTrue(prepared.history().get(1).content().endsWith("用户最新输入（原文）：\n读文件"));
        assertTrue(buffer.toString().contains("模型请求压缩"));
        assertFalse(compactor.beforeRequest(prepared.history(), output).summarized(), "登记只生效一次");
    }

    @Test
    void summaryFailureKeepsHistory() throws Exception {
        ContextCompactor compactor = compactor((m, t) -> {
            throw new IOException("boom");
        }, 10, 3, null);
        List<ChatMessage> history = toolHistory(2);
        ContextCompactor.Prepared prepared = compactor.beforeRequest(history, output);
        assertFalse(prepared.summarized());
        assertSame(history, prepared.history());
        assertTrue(buffer.toString().contains("压缩> 失败（IOException）"), buffer.toString());
    }

    @Test
    void toolCallInsteadOfSummaryIsFailure() throws Exception {
        ContextCompactor compactor = compactor((m, t) -> ChatMessage.assistant(null,
                List.of(new ToolCall("x", "shell", "{}"))), 10, 3, null);
        assertFalse(compactor.beforeRequest(toolHistory(1), output).summarized());
        assertTrue(buffer.toString().contains("压缩> 失败"));
    }

    @Test
    void transcriptFailureSkipsSummaryRequest() throws Exception {
        // 记录目录被同名文件占用：无法保存时不请求总结，也不替换历史。
        Files.writeString(root.resolve(TranscriptStore.DIRECTORY_NAME), "occupied");
        ContextCompactor compactor = compactor((m, t) -> fail("记录未保存时不应总结"), 10, 3, null);
        List<ChatMessage> history = toolHistory(2);
        assertSame(history, compactor.beforeRequest(history, output).history());
        assertTrue(buffer.toString().contains("压缩> 失败"));
    }

    @Test
    void nothingToSummarizeIsSkipped() throws Exception {
        ContextCompactor compactor = compactor((m, t) -> fail("不应请求总结"), 1, 3, null);
        List<ChatMessage> history = List.of(new ChatMessage("system", "系统"), new ChatMessage("user", "很长的输入"));
        assertFalse(compactor.beforeRequest(history, output).summarized());
        assertTrue(buffer.toString().contains("没有可总结的历史"));
    }

    @Test
    void previousSummaryIsSummarizedAgainButNotRepeatedAsInput() throws Exception {
        List<String> rendered = new ArrayList<>();
        ContextCompactor compactor = compactor((m, t) -> {
            rendered.add(m.get(1).content());
            return new ChatMessage("assistant", "第二次摘要");
        }, 10, 3, null);
        List<ChatMessage> history = new ArrayList<>(List.of(new ChatMessage("system", "系统"),
                new ChatMessage("user", ContextCompactor.SUMMARY_MARKER + " 旧摘要")));
        history.add(ChatMessage.assistant(null, List.of(new ToolCall("a", "shell", "{}"))));
        history.add(ChatMessage.tool("a", "结果"));
        String text = compactor.beforeRequest(history, output).history().get(1).content();
        assertTrue(rendered.get(0).contains("旧摘要"));
        assertTrue(text.contains("第二次摘要"));
        assertFalse(text.contains("用户最新输入"), text);
    }

    @Test
    void renderKeepsRecentPartWhenTooLong() {
        String rendered = ContextCompactor.render(List.of(new ChatMessage("user", "最早"),
                new ChatMessage("assistant", "y".repeat(ContextCompactor.MAX_SUMMARY_INPUT_CHARS)),
                new ChatMessage("user", "最新")));
        assertTrue(rendered.startsWith("…（较早的记录已省略）"));
        assertFalse(rendered.contains("最早"));
        assertTrue(rendered.contains("最新"));
    }

    @Test
    void rejectsInvalidSettings() {
        ModelClient client = (m, t) -> null;
        TranscriptStore store = new TranscriptStore(root);
        assertThrows(IllegalArgumentException.class, () -> new ContextCompactor(client, store, root, 0, 3, null));
        assertThrows(IllegalArgumentException.class, () -> new ContextCompactor(client, store, root, 10, -1, null));
    }

    @Test
    void compactNowIgnoresThresholdAndPassesFocus() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        ContextCompactor compactor = compactor((messages, tools) -> {
            requests.add(messages);
            return new ChatMessage("assistant", "摘要");
        }, 100000, 3, null);
        List<ChatMessage> history = new ArrayList<>(toolHistory(1));
        history.add(ChatMessage.assistant("读完了", List.of()));
        ContextCompactor.Prepared prepared = compactor.compactNow(history, "  保留文件名 f  ", output);
        assertTrue(prepared.summarized(), "远低于阈值也立即总结");
        assertTrue(requests.get(0).get(0).content().endsWith("本次压缩请特别保留：保留文件名 f"),
                requests.get(0).get(0).content());
        assertTrue(buffer.toString().contains("压缩> 用户执行 /compact，保存完整记录并请求模型总结"), buffer.toString());
        // 两轮之间执行：最后一条是助手回复，整段对话都交给总结，最近一条用户输入原样附在摘要后。
        assertTrue(requests.get(0).get(1).content().contains("[assistant] 读完了"));
        assertTrue(prepared.history().get(1).content().endsWith("用户最新输入（原文）：\n读文件"));
        assertFalse(compactor.pending(), "不走 compact 工具的登记流程");
    }

    @Test
    void compactNowWithoutConversationOrFailedSummaryKeepsHistory() throws Exception {
        ContextCompactor empty = compactor((m, t) -> fail("没有可总结内容时不请求"), 100000, 3, null);
        List<ChatMessage> onlySystem = List.of(new ChatMessage("system", "系统"));
        assertFalse(empty.compactNow(onlySystem, "", output).summarized());
        assertTrue(buffer.toString().contains("没有可总结的历史，跳过"), buffer.toString());

        ContextCompactor failing = compactor((m, t) -> {
            throw new IOException("boom");
        }, 100000, 3, null);
        List<ChatMessage> history = new ArrayList<>(toolHistory(1));
        history.add(ChatMessage.assistant("读完了", List.of()));
        ContextCompactor.Prepared prepared = failing.compactNow(history, null, output);
        assertFalse(prepared.summarized());
        assertSame(history, prepared.history());
    }

    @Test
    void nonDebugMicroCompactsSilently() throws Exception {
        ContextCompactor compactor = new ContextCompactor((m, t) -> fail("不应请求总结"),
                new TranscriptStore(root.resolve(TranscriptStore.DIRECTORY_NAME)), root, 100000, 1, null, false);
        ContextCompactor.Prepared prepared = compactor.beforeRequest(toolHistory(3), output);
        assertEquals(2, prepared.sent().stream().filter(m -> m.content() != null
                && m.content().startsWith(MicroCompact.PLACEHOLDER_PREFIX)).count(), "发送内容照常微压缩");
        assertEquals("", buffer.toString());
    }

    private ContextCompactor compactor(ModelClient client, int threshold, int keep, Supplier<String> state) {
        return new ContextCompactor(client, new TranscriptStore(root.resolve(TranscriptStore.DIRECTORY_NAME)),
                root, threshold, keep, state);
    }

    /** 系统提示词、用户输入，以及 count 组 read_file 调用与 300 字符结果。 */
    private static List<ChatMessage> toolHistory(int count) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "系统"));
        messages.add(new ChatMessage("user", "读文件"));
        for (int i = 0; i < count; i++) {
            messages.add(ChatMessage.assistant(null, List.of(new ToolCall("c" + i, "read_file", "{\"path\":\"f\"}"))));
            messages.add(ChatMessage.tool("c" + i, "R".repeat(300)));
        }
        return messages;
    }
}
