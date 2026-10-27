package dev.huagent.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** S15：把 HTTP 429/503/529 识别为可重试的限流，把上下文超长错误单独分类，其余保持通用失败。 */
class OpenAiClientRecoveryTest {
    private HttpServer server;
    private int status = 200;
    private String body = "{}";
    private String retryAfter;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                if (retryAfter != null) {
                    exchange.getResponseHeaders().add("Retry-After", retryAfter);
                }
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenAiClient client() {
        return new OpenAiClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key", "test-model", Duration.ofSeconds(5));
    }

    @Test
    void maps429ToModelUnavailableWithRetryAfterHeader() {
        status = 429;
        retryAfter = "5";
        body = "{\"error\":{\"message\":\"rate limited\"}}";
        ModelUnavailableException e = assertThrows(ModelUnavailableException.class,
                () -> client().complete(List.of(new ChatMessage("user", "你好")), List.of()));
        assertEquals(5L, e.retryAfterSeconds());
        assertFalse(e.getMessage().contains("rate limited"));
    }

    @Test
    void maps429WithoutHeaderToRetryableWithNullAdvice() {
        status = 429;
        body = "{}";
        ModelUnavailableException e = assertThrows(ModelUnavailableException.class,
                () -> client().complete(List.of(new ChatMessage("user", "你好")), List.of()));
        assertNull(e.retryAfterSeconds());
    }

    @Test
    void maps503And529ToModelUnavailableToo() {
        status = 503;
        assertThrows(ModelUnavailableException.class,
                () -> client().complete(List.of(new ChatMessage("user", "你好")), List.of()));
        status = 529;
        assertThrows(ModelUnavailableException.class,
                () -> client().complete(List.of(new ChatMessage("user", "你好")), List.of()));
    }

    @Test
    void maps400ContextOverflowBodyToContextTooLarge() {
        status = 400;
        body = "{\"error\":{\"code\":\"context_length_exceeded\","
                + "\"message\":\"This model's maximum context length is 8192 tokens\"}}";
        assertThrows(ContextTooLargeException.class,
                () -> client().complete(List.of(new ChatMessage("user", "你好")), List.of()));
        status = 413;
        body = "{\"error\":{\"message\":\"prompt is too long: 200000 tokens > 8192\"}}";
        assertThrows(ContextTooLargeException.class,
                () -> client().complete(List.of(new ChatMessage("user", "你好")), List.of()));
    }

    @Test
    void keepsOtherFailuresAsPlainIoException() {
        status = 400;
        body = "{\"error\":{\"message\":\"invalid tool schema\"}}";
        IOException e = assertThrows(IOException.class,
                () -> client().complete(List.of(new ChatMessage("user", "你好")), List.of()));
        assertFalse(e instanceof ContextTooLargeException);
        assertFalse(e instanceof ModelUnavailableException);
        status = 500;
        assertThrows(IOException.class,
                () -> client().complete(List.of(new ChatMessage("user", "你好")), List.of()));
    }

    @Test
    void contextOverflowBodyIsNeverReportedVerbatim() {
        status = 400;
        body = "{\"error\":{\"message\":\"maximum context length exceeded, your key sk-abc is invalid\"}}";
        IOException e = assertThrows(ContextTooLargeException.class,
                () -> client().complete(List.of(new ChatMessage("user", "你好")), List.of()));
        assertFalse(e.getMessage().contains("sk-abc"));
    }
}
