package dev.huagent.model;

import dev.huagent.tool.ToolCall;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.huagent.model.StreamServer.event;
import static org.junit.jupiter.api.Assertions.*;

/** 流式 Messages：文本块的增量与非流式的拼接结果一致，工具参数由 input_json_delta 拼成，错误事件按 ModelError 分类。 */
class AnthropicClientStreamTest {
    private static final List<ChatMessage> HELLO = List.of(new ChatMessage("user", "你好"));
    private static final String START = event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"m\","
            + "\"type\":\"message\",\"role\":\"assistant\",\"content\":[],"
            + "\"usage\":{\"input_tokens\":100,\"cache_read_input_tokens\":50,\"output_tokens\":1}}}");
    private static final String STOP = event("message_stop", "{\"type\":\"message_stop\"}");
    private StreamServer server;
    private final List<String> deltas = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokens = new AtomicInteger(-1);

    @BeforeEach
    void startServer() throws IOException {
        server = new StreamServer("/v1/messages");
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void textDeltasMatchTheNonStreamingJoinOfTextBlocks() throws Exception {
        List<String> chunks = new ArrayList<>(List.of(START,
                blockStart(0, "{\"type\":\"thinking\",\"thinking\":\"\"}"),
                delta(0, "{\"type\":\"thinking_delta\",\"thinking\":\"想想\"}"),
                blockStop(0)));
        chunks.addAll(textBlock(1, "  ", "第一段"));
        chunks.addAll(textBlock(2, "  \n"));
        chunks.addAll(textBlock(3, "第二", "段"));
        chunks.add(event("ping", "{\"type\":\"ping\"}"));
        chunks.add(messageDelta("end_turn", "{\"output_tokens\":30}"));
        chunks.add(STOP);
        server.chunks = chunks;

        ChatMessage answer = complete();

        assertEquals("  第一段\n\n第二段", answer.content());
        assertEquals(List.of("  第一段", "\n\n第二", "段"), deltas, "块开头的空白等到有文字时一起交出，空白块不交出");
        assertEquals(answer.content(), String.join("", deltas));
        assertEquals(180, tokens.get(), "输入与缓存来自 message_start，输出来自 message_delta");
        assertTrue(server.request.get().getBooleanValue("stream"));
    }

    @Test
    void toolInputIsAssembledFromJsonDeltas() throws Exception {
        List<String> chunks = new ArrayList<>(List.of(START));
        chunks.addAll(textBlock(0, "我来读文件。"));
        chunks.add(blockStart(1, "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_file\",\"input\":{}}"));
        chunks.add(delta(1, "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"pa\"}"));
        chunks.add(delta(1, "{\"type\":\"input_json_delta\",\"partial_json\":\"th\\\": \\\"A.java\\\"}\"}"));
        chunks.add(blockStop(1));
        chunks.add(blockStart(2, "{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"glob\",\"input\":{}}"));
        chunks.add(blockStop(2));
        chunks.add(messageDelta("tool_use", "{\"output_tokens\":40}"));
        chunks.add(STOP);
        server.chunks = chunks;

        ChatMessage answer = complete();

        assertEquals("我来读文件。", answer.content());
        assertEquals(List.of(new ToolCall("toolu_1", "read_file", "{\"path\":\"A.java\"}"),
                new ToolCall("toolu_2", "glob", "{}")), answer.toolCalls());
    }

    @Test
    void errorEventsAreClassifiedByType() {
        List<String> overloaded = new ArrayList<>(List.of(START));
        overloaded.addAll(textBlock(0, "说到"));
        overloaded.add(event("error", "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"));
        server.chunks = overloaded;
        assertTrue(assertThrows(ModelUnavailableException.class, this::complete).getMessage().contains("服务过载"));
        assertEquals(List.of("说到"), deltas);

        server.chunks = List.of(START,
                event("error", "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}"));
        IOException invalid = assertThrows(IOException.class, this::complete);
        assertFalse(invalid instanceof ModelUnavailableException);
    }

    @Test
    void truncatedUnfinishedOrInvalidStreamsFail() {
        List<String> truncated = new ArrayList<>(List.of(START));
        truncated.addAll(textBlock(0, "写到一半"));
        truncated.add(messageDelta("max_tokens", "{\"output_tokens\":8192}"));
        truncated.add(STOP);
        server.chunks = truncated;
        assertEquals("模型响应未完成：max_tokens", assertThrows(IOException.class, this::complete).getMessage());

        List<String> unfinished = new ArrayList<>(List.of(START));
        unfinished.addAll(textBlock(0, "连接断了"));
        server.chunks = unfinished;
        assertThrows(ModelUnavailableException.class, this::complete);

        server.chunks = List.of(START,
                blockStart(0, "{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"glob\",\"input\":{}}"),
                delta(0, "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"pattern\"}"),
                blockStop(0), messageDelta("tool_use", "{}"), STOP);
        assertEquals("模型响应包含无效或重复的工具调用", assertThrows(IOException.class, this::complete).getMessage());

        server.chunks = List.of(START, delta(5, "{\"type\":\"text_delta\",\"text\":\"没有开始\"}"));
        assertThrows(IOException.class, this::complete);
    }

    @Test
    void plainResponseIsParsedAndOldConstructorDoesNotStream() throws Exception {
        server.contentType = "application/json";
        server.chunks = List.of("{\"type\":\"message\",\"role\":\"assistant\",\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"整段\"}],\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}");

        assertEquals("整段", complete().content());
        assertEquals(List.of(), deltas);
        assertEquals(7, tokens.get());

        new AnthropicClient(server.url(), "k", "m", 1024, false, Duration.ofSeconds(5)).complete(HELLO, List.of());
        assertFalse(server.request.get().containsKey("stream"), "旧构造器不流式");
    }

    private ChatMessage complete() throws IOException, InterruptedException {
        deltas.clear();
        tokens.set(-1);
        return new AnthropicClient(server.url(), "test-key", "test-model", 1024, false, Duration.ofSeconds(5), true)
                .complete(HELLO, List.of(), tokens::set, deltas::add);
    }

    private static List<String> textBlock(int index, String... parts) {
        List<String> chunks = new ArrayList<>();
        chunks.add(blockStart(index, "{\"type\":\"text\",\"text\":\"\"}"));
        for (String part : parts) {
            chunks.add(delta(index, "{\"type\":\"text_delta\",\"text\":" + JSON.toJSONString(part) + "}"));
        }
        chunks.add(blockStop(index));
        return chunks;
    }

    private static String blockStart(int index, String block) {
        return event("content_block_start", "{\"type\":\"content_block_start\",\"index\":" + index
                + ",\"content_block\":" + block + "}");
    }

    private static String delta(int index, String delta) {
        return event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":" + index
                + ",\"delta\":" + delta + "}");
    }

    private static String blockStop(int index) {
        return event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":" + index + "}");
    }

    private static String messageDelta(String stopReason, String usage) {
        return event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason
                + "\",\"stop_sequence\":null},\"usage\":" + usage + "}");
    }
}
