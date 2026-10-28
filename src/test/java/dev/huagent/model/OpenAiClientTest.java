package dev.huagent.model;

import dev.huagent.tool.ShellTool;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiClientTest {
    private HttpServer server;
    private String baseUrl;
    private int status = 200;
    private String response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你好，小李\"}}]}";
    private long delayMillis;
    private final AtomicReference<JSONObject> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                method.set(exchange.getRequestMethod());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                request.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsChatCompletionWithUtf8HistoryAndNoTools() throws Exception {
        ChatMessage answer = client(baseUrl + "/", Duration.ofSeconds(5)).complete(List.of(
                new ChatMessage("system", "请用中文回答"), new ChatMessage("user", "你好")), List.of());

        assertEquals("你好，小李", answer.content());
        assertEquals("POST", method.get());
        assertEquals("Bearer test-key", authorization.get());
        assertEquals("test-model", request.get().getString("model"));
        assertFalse(request.get().getBooleanValue("stream"));
        assertFalse(request.get().containsKey("tools"));
        assertEquals("你好", request.get().getJSONArray("messages").getJSONObject(1).getString("content"));
    }

    @Test
    void reportsContextTokensFromUsage() throws Exception {
        response = """
                {"choices":[{"message":{"role":"assistant","content":"好"}}],
                 "usage":{"prompt_tokens":1200,"completion_tokens":80,"total_tokens":1280}}
                """;
        List<Integer> reported = new java.util.ArrayList<>();
        ChatMessage answer = client(baseUrl, Duration.ofSeconds(5)).complete(List.of(new ChatMessage("user", "你好")),
                List.of(), reported::add);
        assertEquals("好", answer.content());
        assertEquals(List.of(1280), reported);
    }

    @Test
    void contextTokensExcludeReasoningAndToleratesMissingUsage() {
        assertEquals(1250, OpenAiClient.contextTokens("""
                {"usage":{"prompt_tokens":1200,"completion_tokens":530,
                 "completion_tokens_details":{"reasoning_tokens":480}}}
                """));
        assertEquals(1200, OpenAiClient.contextTokens("{\"usage\":{\"prompt_tokens\":1200}}"));
        assertEquals(-1, OpenAiClient.contextTokens("{\"choices\":[]}"));
        assertEquals(-1, OpenAiClient.contextTokens("{\"usage\":{\"prompt_tokens\":\"many\"}}"));
        assertEquals(-1, OpenAiClient.contextTokens("{\"usage\":null}"));
    }

    @Test
    void parsesMultipleToolCallsWithNullContentAndSerializesProtocolFields() throws Exception {
        response = """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                "tool_calls":[
                  {"id":"a","type":"function","function":{"name":"shell","arguments":"{\\"command\\":\\"pwd\\"}"}},
                  {"id":"b","type":"function","function":{"name":"shell","arguments":"broken json"}}]}}]}
                """;
        ToolDefinition definition = new ShellTool(java.nio.file.Path.of("playground"), Duration.ofSeconds(30), 12000)
                .definition();
        OpenAiClient client = client(baseUrl, Duration.ofSeconds(5));
        ChatMessage answer = client.complete(List.of(new ChatMessage("user", "目录")), List.of(definition));
        assertNull(answer.content());
        assertEquals(2, answer.toolCalls().size());
        assertEquals(new ToolCall("a", "shell", "{\"command\":\"pwd\"}"), answer.toolCalls().get(0));
        assertEquals("broken json", answer.toolCalls().get(1).arguments());
        JSONObject tool = request.get().getJSONArray("tools").getJSONObject(0);
        assertEquals("function", tool.getString("type"));
        assertEquals("shell", tool.getJSONObject("function").getString("name"));
        assertEquals("command", tool.getJSONObject("function").getJSONObject("parameters")
                .getJSONArray("required").getString(0));
        assertEquals("auto", request.get().getString("tool_choice"));

        response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"完成\"}}]}";
        client.complete(List.of(new ChatMessage("user", "目录"), answer,
                ChatMessage.tool("a", "中文结果"), ChatMessage.tool("b", "参数错误")), List.of(definition));
        JSONObject sentAssistant = request.get().getJSONArray("messages").getJSONObject(1);
        assertFalse(sentAssistant.containsKey("toolCalls"));
        assertFalse(sentAssistant.containsKey("toolCallId"));
        JSONObject sentCall = sentAssistant.getJSONArray("tool_calls").getJSONObject(0);
        assertEquals("a", sentCall.getString("id"));
        assertEquals("function", sentCall.getString("type"));
        assertInstanceOf(String.class, sentCall.getJSONObject("function").get("arguments"));
        JSONObject sentResult = request.get().getJSONArray("messages").getJSONObject(2);
        assertEquals("tool", sentResult.getString("role"));
        assertEquals("a", sentResult.getString("tool_call_id"));
        assertEquals("中文结果", sentResult.getString("content"));
        assertFalse(sentResult.containsKey("tool_calls"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"shell\",\"arguments\":{}}}",
            "{\"id\":\"\",\"type\":\"function\",\"function\":{\"name\":\"shell\",\"arguments\":\"{}\"}}",
            "{\"id\":\"a\",\"type\":\"other\",\"function\":{\"name\":\"shell\",\"arguments\":\"{}\"}}",
            "{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"\",\"arguments\":\"{}\"}}"})
    void rejectsMalformedToolCall(String call) {
        response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[" + call + "]}}]}";
        assertThrows(IOException.class, () -> client(baseUrl, Duration.ofSeconds(5)).complete(List.of(), List.of()));
    }

    @Test
    void rejectsDuplicateCallIds() {
        String call = "{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"shell\",\"arguments\":\"{}\"}}";
        response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[" + call + "," + call + "]}}]}";
        assertThrows(IOException.class, () -> client(baseUrl, Duration.ofSeconds(5)).complete(List.of(), List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter", "tool_calls"})
    void rejectsIncompleteResponsesBeforeCommandsCanRun(String reason) {
        response = "{\"choices\":[{\"finish_reason\":\"" + reason
                + "\",\"message\":{\"role\":\"assistant\",\"content\":\"incomplete\"}}]}";
        assertThrows(IOException.class, () -> client(baseUrl, Duration.ofSeconds(5)).complete(List.of(), List.of()));
    }

    @Test
    void httpErrorIncludesStatusButDoesNotEchoProviderBody() {
        status = 401;
        response = "private provider details test-key";
        IOException error = assertThrows(IOException.class, () -> client(baseUrl, Duration.ofSeconds(5)).complete(List.of(), List.of()));
        assertTrue(error.getMessage().contains("401"));
        assertFalse(error.getMessage().contains("test-key"));
        assertFalse(error.getMessage().contains("private"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "null", "{}", "{\"choices\":[]}",
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null}}]}",
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":{}}}]}",
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{}]}}]}"})
    void rejectsMissingOrMalformedTextResponses(String body) {
        response = body;
        assertThrows(IOException.class, () -> client(baseUrl, Duration.ofSeconds(5)).complete(List.of(), List.of()));
    }

    @Test
    void requestHasTimeout() {
        delayMillis = 500;
        assertThrows(HttpTimeoutException.class,
                () -> client(baseUrl, Duration.ofMillis(100)).complete(List.of(), List.of()));
    }

    private OpenAiClient client(String url, Duration timeout) {
        return new OpenAiClient(url, "test-key", "test-model", timeout);
    }
}
