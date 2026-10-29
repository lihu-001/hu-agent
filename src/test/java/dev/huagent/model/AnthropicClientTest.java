package dev.huagent.model;

import dev.huagent.config.ModelConfig;
import dev.huagent.config.ModelProvider;
import dev.huagent.tool.ShellTool;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicClientTest {
    private HttpServer server;
    private String root;
    private int status = 200;
    private String retryAfter;
    private String response = """
            {"type":"message","role":"assistant","stop_reason":"end_turn",
             "content":[{"type":"text","text":"你好，小李"}]}
            """;
    private final AtomicReference<JSONObject> request = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> version = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final CountDownLatch requestReceived = new CountDownLatch(1);
    private final CountDownLatch responseRelease = new CountDownLatch(1);
    private volatile boolean holdResponse;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                path.set(exchange.getRequestURI().getPath());
                apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
                version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                request.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                if (holdResponse) {
                    requestReceived.countDown();
                    try {
                        if (!responseRelease.await(10, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting to release response");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                if (retryAfter != null) {
                    exchange.getResponseHeaders().add("Retry-After", retryAfter);
                }
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        root = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsMessagesRequestWithTopLevelSystemAndApiKeyHeader() throws Exception {
        ChatMessage answer = client(root + "/", false).complete(List.of(new ChatMessage("system", "请用中文回答"),
                new ChatMessage("system", "工作目录 /tmp"), new ChatMessage("user", "你好")), List.of());

        assertEquals("你好，小李", answer.content());
        assertEquals("/v1/messages", path.get());
        assertEquals("test-key", apiKey.get());
        assertEquals(AnthropicClient.API_VERSION, version.get());
        assertNull(authorization.get(), "官方接口只发送 x-api-key");
        JSONObject body = request.get();
        assertEquals("test-model", body.getString("model"));
        assertEquals(1024, body.getIntValue("max_tokens"));
        assertEquals("请用中文回答\n\n工作目录 /tmp", body.getString("system"));
        assertFalse(body.containsKey("tools"));
        JSONArray messages = body.getJSONArray("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.getJSONObject(0).getString("role"));
        assertEquals(JSONArray.of(JSONObject.of("type", "text", "text", "你好")),
                messages.getJSONObject(0).getJSONArray("content"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void switchesSubsequentRequestsWithoutChangingInFlightRequest(boolean bearer) throws Exception {
        AnthropicClient client = client(root, bearer);
        List<ChatMessage> history = List.of(new ChatMessage("user", "你好"));
        holdResponse = true;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var inFlight = executor.submit(() -> client.complete(history, List.of()));
            try {
                assertTrue(requestReceived.await(5, TimeUnit.SECONDS));
                assertFalse(inFlight.isDone());
                assertEquals("test-model", request.get().getString("model"));
                assertEquals(1024, request.get().getIntValue("max_tokens"));
                assertEquals("test-key", apiKey.get());
                assertEquals(bearer ? "Bearer test-key" : null, authorization.get());
                assertEquals(AnthropicClient.API_VERSION, version.get());

                client.selectModel("provider/next-model");

                assertEquals("provider/next-model", client.model());
                assertEquals("test-model", request.get().getString("model"));
                assertEquals(1024, request.get().getIntValue("max_tokens"));
                assertFalse(inFlight.isDone());
            } finally {
                responseRelease.countDown();
            }
            assertEquals("你好，小李", inFlight.get(5, TimeUnit.SECONDS).content());
        }
        JSONObject firstRequest = request.get();
        client.complete(history, List.of(), tokens -> {});
        assertEquals("provider/next-model", request.get().getString("model"));
        assertEquals(1024, request.get().getIntValue("max_tokens"));
        assertEquals("test-key", apiKey.get());
        assertEquals(bearer ? "Bearer test-key" : null, authorization.get());
        assertEquals(AnthropicClient.API_VERSION, version.get());
        assertEquals("/v1/messages", path.get());
        assertEquals(firstRequest.getJSONArray("messages"), request.get().getJSONArray("messages"));

        client.selectModel("provider/third-model");
        client.complete(history, List.of());
        assertEquals("provider/third-model", request.get().getString("model"));
        assertEquals(1024, request.get().getIntValue("max_tokens"));
        assertEquals("test-key", apiKey.get());
        assertEquals(bearer ? "Bearer test-key" : null, authorization.get());
        assertEquals(AnthropicClient.API_VERSION, version.get());
        assertEquals("/v1/messages", path.get());
        assertEquals("test-model", firstRequest.getString("model"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " leading", "trailing ", "two names", "a\tb", "a\nb", "a\rb",
            "a\0b", "a\033b", "a\177b", "a\u0085b", "a\u00a0b", "a\u2003b", "a\u202fb",
            "a\u200bb", "a\ufeffb", "a\u202eb"})
    void invalidSelectionPreservesModelForNextRequest(String name) throws Exception {
        AnthropicClient client = client(root, true);
        client.selectModel("provider/selected");
        assertNull(request.get());

        assertThrows(IllegalArgumentException.class, () -> client.selectModel(name));

        assertEquals("provider/selected", client.model());
        assertNull(request.get());
        client.complete(List.of(new ChatMessage("user", "你好")), List.of());
        assertEquals("provider/selected", request.get().getString("model"));
        assertEquals(1024, request.get().getIntValue("max_tokens"));
        assertEquals("test-key", apiKey.get());
        assertEquals("Bearer test-key", authorization.get());
    }

    @Test
    void baseUrlEndingWithV1AndCompatibleBearerHeader() throws Exception {
        client(root + "/anthropic/v1", true).complete(List.of(new ChatMessage("user", "你好")), List.of());
        assertEquals("/anthropic/v1/messages", path.get());
        assertEquals("test-key", apiKey.get());
        assertEquals("Bearer test-key", authorization.get());

        client(root + "/anthropic", true).complete(List.of(new ChatMessage("user", "你好")), List.of());
        assertEquals("/anthropic/v1/messages", path.get());
    }

    @Test
    void convertsToolHistoryIntoContentBlocks() throws Exception {
        ToolDefinition shell = new ShellTool(Path.of("playground"), Duration.ofSeconds(30), 12000).definition();
        ChatMessage assistant = ChatMessage.assistant("我看看目录", List.of(
                new ToolCall("a", "shell", "{\"command\":\"pwd\"}"), new ToolCall("b", "shell", "broken json")));
        client(root, false).complete(List.of(new ChatMessage("system", "s"), new ChatMessage("user", "目录"),
                assistant, ChatMessage.tool("a", "中文结果"), ChatMessage.tool("b", ""),
                new ChatMessage("user", "提醒：更新清单")), List.of(shell));

        JSONArray messages = request.get().getJSONArray("messages");
        assertEquals(3, messages.size(), "工具结果与随后的用户消息合并为一条 user 消息");
        JSONArray sentAssistant = messages.getJSONObject(1).getJSONArray("content");
        assertEquals("assistant", messages.getJSONObject(1).getString("role"));
        assertEquals(JSONObject.of("type", "text", "text", "我看看目录"), sentAssistant.getJSONObject(0));
        assertEquals(JSONObject.of("type", "tool_use", "id", "a", "name", "shell",
                "input", JSONObject.of("command", "pwd")), sentAssistant.getJSONObject(1));
        assertEquals(new JSONObject(), sentAssistant.getJSONObject(2).getJSONObject("input"), "无法解析的参数以空对象发送");

        JSONObject results = messages.getJSONObject(2);
        assertEquals("user", results.getString("role"));
        JSONArray blocks = results.getJSONArray("content");
        assertEquals(JSONObject.of("type", "tool_result", "tool_use_id", "a", "content", "中文结果"), blocks.getJSONObject(0));
        assertEquals(JSONObject.of("type", "tool_result", "tool_use_id", "b"), blocks.getJSONObject(1));
        assertEquals(JSONObject.of("type", "text", "text", "提醒：更新清单"), blocks.getJSONObject(2));

        JSONObject tool = request.get().getJSONArray("tools").getJSONObject(0);
        assertEquals("shell", tool.getString("name"));
        assertEquals("command", tool.getJSONObject("input_schema").getJSONArray("required").getString(0));
        assertEquals(JSONObject.of("type", "auto"), request.get().getJSONObject("tool_choice"));
    }

    @Test
    void parsesTextAndToolUseBlocksAndReportsContextTokens() throws Exception {
        response = """
                {"role":"assistant","stop_reason":"tool_use","content":[
                  {"type":"thinking","thinking":"..."},
                  {"type":"text","text":"先看目录"},
                  {"type":"tool_use","id":"toolu_1","name":"shell","input":{"command":"pwd"}},
                  {"type":"tool_use","id":"toolu_2","name":"read_file","input":{}}],
                 "usage":{"input_tokens":100,"cache_creation_input_tokens":20,"cache_read_input_tokens":1000,
                          "output_tokens":30}}
                """;
        List<Integer> reported = new ArrayList<>();
        ChatMessage answer = client(root, false).complete(List.of(new ChatMessage("user", "目录")), List.of(),
                reported::add);
        assertEquals("先看目录", answer.content());
        assertEquals(List.of(new ToolCall("toolu_1", "shell", "{\"command\":\"pwd\"}"),
                new ToolCall("toolu_2", "read_file", "{}")), answer.toolCalls());
        assertEquals(List.of(1150), reported);
        assertEquals(-1, AnthropicClient.contextTokens("{\"usage\":{\"output_tokens\":3}}"));
        assertEquals(-1, AnthropicClient.contextTokens("not json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"max_tokens", "refusal", "tool_use"})
    void rejectsIncompleteResponsesBeforeCommandsCanRun(String reason) {
        response = "{\"role\":\"assistant\",\"stop_reason\":\"" + reason
                + "\",\"content\":[{\"type\":\"text\",\"text\":\"incomplete\"}]}";
        IOException error = assertThrows(IOException.class, () -> client(root, false).complete(List.of(), List.of()));
        assertFalse(error instanceof EmptyReplyException, "不完整的响应不当作空回复重试");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[{\"type\":\"text\",\"text\":\"  \"}]",
            "[{\"type\":\"thinking\",\"thinking\":\"想一想\"}]"})
    void emptyReplyIsReportedSeparatelySoChatLoopCanRetry(String content) {
        response = "{\"role\":\"assistant\",\"stop_reason\":\"end_turn\",\"content\":" + content + "}";
        assertThrows(EmptyReplyException.class, () -> client(root, false).complete(List.of(), List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "null", "{}", "{\"role\":\"assistant\"}",
            "{\"role\":\"assistant\",\"content\":[]}",
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"  \"}]}",
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":{}}]}",
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"shell\",\"input\":\"{}\"}]}",
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"\",\"name\":\"shell\",\"input\":{}}]}",
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"s\",\"input\":{}},"
                    + "{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"s\",\"input\":{}}]}"})
    void rejectsMissingOrMalformedResponses(String body) {
        response = body;
        assertThrows(IOException.class, () -> client(root, false).complete(List.of(), List.of()));
    }

    @Test
    void classifiesRateLimitOverloadAndContextOverflow() {
        status = 429;
        retryAfter = "7";
        response = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}";
        ModelUnavailableException limited = assertThrows(ModelUnavailableException.class,
                () -> client(root, false).complete(List.of(new ChatMessage("user", "你好")), List.of()));
        assertEquals(7L, limited.retryAfterSeconds());
        assertFalse(limited.getMessage().contains("slow down"));

        status = 529;
        retryAfter = null;
        assertThrows(ModelUnavailableException.class,
                () -> client(root, false).complete(List.of(new ChatMessage("user", "你好")), List.of()));

        status = 400;
        response = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"prompt is too long: 210000 tokens > 200000 maximum\"}}";
        assertThrows(ContextTooLargeException.class,
                () -> client(root, false).complete(List.of(new ChatMessage("user", "你好")), List.of()));

        status = 401;
        response = "private provider details test-key";
        IOException error = assertThrows(IOException.class,
                () -> client(root, false).complete(List.of(new ChatMessage("user", "你好")), List.of()));
        assertTrue(error.getMessage().contains("401"));
        assertFalse(error.getMessage().contains("test-key"));
    }

    @Test
    void rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> new AnthropicClient("ftp://example", "k", "m", 1024, false, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new AnthropicClient(root, "k\nx", "m", 1024, false, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new AnthropicClient(root, "k", "m", 0, false, Duration.ofSeconds(5)));
    }

    @Test
    void factoryPicksClientByProtocolAndSendsBearerOnlyForCompatibleProviders() throws Exception {
        Duration timeout = Duration.ofSeconds(5);
        assertInstanceOf(OpenAiClient.class, ModelClients.create(
                new ModelConfig(ModelProvider.OPENAI, "https://api.openai.com/v1", "k", "m", 1000, 0), timeout));
        assertInstanceOf(OpenAiClient.class, ModelClients.create(
                new ModelConfig(ModelProvider.OPENAI_COMPATIBLE, root + "/v1", "k", "m", 1000, 0), timeout));

        ModelClients.create(new ModelConfig(ModelProvider.ANTHROPIC, root, "test-key", "m", 1000, 32000), timeout)
                .complete(List.of(new ChatMessage("user", "你好")), List.of());
        assertNull(authorization.get());
        assertEquals(32000, request.get().getIntValue("max_tokens"));

        ModelClients.create(new ModelConfig(ModelProvider.ANTHROPIC_COMPATIBLE, root, "test-key", "m", 1000, 8192),
                timeout).complete(List.of(new ChatMessage("user", "你好")), List.of());
        assertEquals("Bearer test-key", authorization.get());
        assertEquals(8192, request.get().getIntValue("max_tokens"));
    }

    @Test
    void listModelsFollowsPagesWithSameHeaders() throws Exception {
        List<String> queries = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<String> listKey = new AtomicReference<>();
        AtomicReference<String> listVersion = new AtomicReference<>();
        AtomicReference<String> listAuthorization = new AtomicReference<>();
        server.createContext("/v1/models", exchange -> {
            try (exchange) {
                String query = exchange.getRequestURI().getRawQuery();
                queries.add(query);
                listKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
                listVersion.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
                listAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                JSONObject page = query.contains("after_id")
                        ? JSONObject.of("data", List.of(JSONObject.of("id", "claude-haiku-5-5")), "has_more", false,
                        "last_id", "claude-haiku-5-5")
                        : JSONObject.of("data", List.of(JSONObject.of("id", "claude-opus-5-5", "type", "model"),
                        JSONObject.of("id", "claude-sonnet-5-5")), "has_more", true, "last_id", "claude-sonnet-5-5");
                byte[] body = JSON.toJSONBytes(page);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });

        assertEquals(List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"),
                client(root + "/v1", true).listModels());
        assertEquals(List.of("limit=" + AnthropicClient.MODELS_PAGE_SIZE,
                "limit=" + AnthropicClient.MODELS_PAGE_SIZE + "&after_id=claude-sonnet-5-5"), queries);
        assertEquals("test-key", listKey.get());
        assertEquals(AnthropicClient.API_VERSION, listVersion.get());
        assertEquals("Bearer test-key", listAuthorization.get());
        assertNull(request.get(), "列出模型不发对话请求");
    }

    @Test
    void listModelsStopsAfterMaxPages() throws Exception {
        List<String> queries = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/v1/models", exchange -> {
            try (exchange) {
                queries.add(exchange.getRequestURI().getRawQuery());
                byte[] body = JSON.toJSONBytes(JSONObject.of("data", List.of(JSONObject.of("id", "m")),
                        "has_more", true, "last_id", "m"));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });

        assertEquals(AnthropicClient.MODELS_MAX_PAGES, client(root, false).listModels().size());
        assertEquals(AnthropicClient.MODELS_MAX_PAGES, queries.size());
    }

    private AnthropicClient client(String url, boolean bearer) {
        return new AnthropicClient(url, "test-key", "test-model", 1024, bearer, Duration.ofSeconds(5));
    }
}
