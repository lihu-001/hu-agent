package dev.huagent.model;

import dev.huagent.tool.ToolCall;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.huagent.model.StreamServer.data;
import static org.junit.jupiter.api.Assertions.*;

/** 流式 Chat Completions：增量按顺序交出，工具调用片段拼接，usage 来自最后一个事件，失败与非流式一样分类。 */
class OpenAiClientStreamTest {
    private static final List<ChatMessage> HELLO = List.of(new ChatMessage("user", "你好"));
    private StreamServer server;
    private final List<String> deltas = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokens = new AtomicInteger(-1);

    @BeforeEach
    void startServer() throws IOException {
        server = new StreamServer("/v1/chat/completions");
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void streamsTextInOrderAndReadsUsageFromTheLastEvent() throws Exception {
        server.chunks = List.of(
                data("{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}"),
                content("你好"),
                content("，小李\n第二行"),
                finish("stop"),
                data("{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}"),
                data("[DONE]"));

        ChatMessage answer = complete(Duration.ofSeconds(5));

        assertEquals("你好，小李\n第二行", answer.content());
        assertEquals(List.of("你好", "，小李\n第二行"), deltas);
        assertEquals(120, tokens.get());
        JSONObject body = server.request.get();
        assertTrue(body.getBooleanValue("stream"));
        assertTrue(body.getJSONObject("stream_options").getBooleanValue("include_usage"));
    }

    @Test
    void assemblesToolCallsFromFragments() throws Exception {
        server.chunks = List.of(
                content("我先看看。"),
                calls("{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}"),
                calls("{\"index\":1,\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"glob\"}}"),
                calls("{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\"}}"),
                calls("{\"index\":0,\"function\":{\"arguments\":\"\\\"A.java\\\"}\"}}"),
                finish("tool_calls"),
                data("[DONE]"));

        ChatMessage answer = complete(Duration.ofSeconds(5));

        assertEquals("我先看看。", answer.content());
        assertEquals(List.of(new ToolCall("call_a", "read_file", "{\"path\":\"A.java\"}"),
                new ToolCall("call_b", "glob", "{}")), answer.toolCalls());
        assertEquals(-1, tokens.get(), "没有 usage 时用量未知");
    }

    @Test
    void toolCallsWithoutIndexAreSeparatedById() throws Exception {
        server.chunks = List.of(
                calls("{\"id\":\"a\",\"function\":{\"name\":\"glob\",\"arguments\":\"{\\\"pattern\\\":\\\"*\\\"}\"}}"),
                calls("{\"id\":\"b\",\"function\":{\"name\":\"grep\",\"arguments\":\"{}\"}}"),
                finish("tool_calls"));

        ChatMessage answer = complete(Duration.ofSeconds(5));

        assertNull(answer.content());
        assertEquals(List.of(new ToolCall("a", "glob", "{\"pattern\":\"*\"}"), new ToolCall("b", "grep", "{}")),
                answer.toolCalls());
    }

    @Test
    void truncatedOrEmptyAnswersFailLikeNonStreaming() {
        server.chunks = List.of(content("写到一半"), finish("length"), data("[DONE]"));
        IOException truncated = assertThrows(IOException.class, () -> complete(Duration.ofSeconds(5)));
        assertEquals("模型响应未完成：length", truncated.getMessage());
        assertEquals(List.of("写到一半"), deltas, "截断前的文字已经交出");

        server.chunks = List.of(content("  \n"), finish("stop"), data("[DONE]"));
        assertThrows(EmptyReplyException.class, () -> complete(Duration.ofSeconds(5)));
    }

    @Test
    void errorEventsAndUnfinishedStreamsAreClassified() {
        server.chunks = List.of(content("你"), data("{\"error\":{\"message\":\"upstream\",\"code\":502}}"));
        ModelUnavailableException upstream = assertThrows(ModelUnavailableException.class,
                () -> complete(Duration.ofSeconds(5)));
        assertFalse(upstream.getMessage().contains("upstream"), "错误正文不进入消息");

        server.chunks = List.of(data("{\"error\":{\"message\":\"no\"}}"));
        assertThrows(ModelUnavailableException.class, () -> complete(Duration.ofSeconds(5)), "认不出的错误按服务端错误");

        server.chunks = List.of(data("{\"error\":{\"code\":400}}"));
        IOException invalid = assertThrows(IOException.class, () -> complete(Duration.ofSeconds(5)));
        assertFalse(invalid instanceof ModelUnavailableException);

        server.chunks = List.of(content("说到一半连接就关了"));
        IOException unfinished = assertThrows(ModelUnavailableException.class, () -> complete(Duration.ofSeconds(5)));
        assertTrue(unfinished.getMessage().contains("没有正常结束"), unfinished.getMessage());

        server.chunks = List.of(data("不是 JSON"));
        assertEquals("模型的流式响应不是有效的 Chat Completions JSON",
                assertThrows(IOException.class, () -> complete(Duration.ofSeconds(5))).getMessage());
    }

    @Test
    void plainResponseIsParsedWhenTheServerIgnoresStream() throws Exception {
        server.contentType = "application/json; charset=utf-8";
        server.chunks = List.of("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"整段回答\"}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}");

        ChatMessage answer = complete(Duration.ofSeconds(5));

        assertEquals("整段回答", answer.content());
        assertEquals(List.of(), deltas, "非流式响应不经过增量");
        assertEquals(10, tokens.get());
    }

    @Test
    void httpErrorsAreClassifiedLikeNonStreaming() {
        server.contentType = "application/json";
        server.status = 429;
        server.retryAfter = "7";
        server.chunks = List.of("{\"error\":{\"message\":\"slow down\"}}");
        assertEquals(7L, assertThrows(ModelUnavailableException.class,
                () -> complete(Duration.ofSeconds(5))).retryAfterSeconds());

        server.status = 400;
        server.retryAfter = null;
        server.chunks = List.of("{\"error\":{\"code\":\"context_length_exceeded\"}}");
        assertThrows(ContextTooLargeException.class, () -> complete(Duration.ofSeconds(5)));
    }

    @Test
    void silentStreamTimesOutAfterIdleTimeout() {
        server.chunks = List.of(content("你好"));
        server.stallAfter = 1;
        long start = System.nanoTime();

        assertThrows(HttpTimeoutException.class, () -> complete(Duration.ofMillis(500)));

        assertEquals(List.of("你好"), deltas);
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "空闲超时后应立即结束");
    }

    @Test
    void interruptStopsReadingPromptly() throws Exception {
        server.chunks = List.of(content("你好"));
        server.stallAfter = 1;
        CountDownLatch first = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> stillInterrupted = new AtomicReference<>();
        OpenAiClient client = new OpenAiClient(server.url() + "/v1", "test-key", "test-model",
                Duration.ofSeconds(30), true);
        Thread worker = new Thread(() -> {
            try {
                client.complete(HELLO, List.of(), value -> {
                }, delta -> first.countDown());
            } catch (Throwable e) {
                failure.set(e);
                stillInterrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        assertTrue(first.await(5, TimeUnit.SECONDS));

        worker.interrupt();
        worker.join(5000);

        assertFalse(worker.isAlive(), "中断后应停止读取");
        assertInstanceOf(InterruptedException.class, failure.get());
        assertFalse(stillInterrupted.get(), "抛出 InterruptedException 时清除中断状态");
    }

    private ChatMessage complete(Duration timeout) throws IOException, InterruptedException {
        deltas.clear();
        tokens.set(-1);
        return new OpenAiClient(server.url() + "/v1", "test-key", "test-model", timeout, true)
                .complete(HELLO, List.of(), tokens::set, deltas::add);
    }

    private static String content(String text) {
        return data("{\"choices\":[{\"index\":0,\"delta\":{\"content\":" + JSON.toJSONString(text)
                + "},\"finish_reason\":null}]}");
    }

    private static String calls(String call) {
        return data("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" + call + "]},\"finish_reason\":null}]}");
    }

    private static String finish(String reason) {
        return data("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + reason + "\"}]}");
    }
}
