package dev.huagent.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** S15：按 ModelError 把可重试的错误码与连接失败分类为 ModelUnavailableException，上下文超长单独分类，其余保持通用失败。 */
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
    void mapsServerErrorsAndTimeoutsToModelUnavailableToo() {
        for (int code : new int[]{408, 409, 500, 502, 503, 504, 520, 529}) {
            status = code;
            ModelUnavailableException e = assertThrows(ModelUnavailableException.class,
                    () -> client().complete(List.of(new ChatMessage("user", "你好")), List.of()), "HTTP " + code);
            assertEquals("模型接口返回 HTTP " + code + "（" + ModelError.of(code).label() + "）", e.getMessage());
        }
    }

    @Test
    void mapsConnectionFailureToModelUnavailable() throws IOException {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = closed.getLocalPort();
        }
        OpenAiClient unreachable = new OpenAiClient("http://127.0.0.1:" + port + "/v1",
                "test-key", "test-model", Duration.ofSeconds(5));
        ModelUnavailableException e = assertThrows(ModelUnavailableException.class,
                () -> unreachable.complete(List.of(new ChatMessage("user", "你好")), List.of()));
        assertTrue(e.getMessage().startsWith("连接模型接口失败（"), e.getMessage());
        assertNull(e.retryAfterSeconds());
        assertTrue(ModelError.retryable(e));
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
        assertEquals("模型接口返回 HTTP 400（请求无效）", e.getMessage());
        for (int code : new int[]{401, 403, 404, 418}) {
            status = code;
            IOException other = assertThrows(IOException.class,
                    () -> client().complete(List.of(new ChatMessage("user", "你好")), List.of()));
            assertFalse(ModelError.retryable(other), "HTTP " + code);
        }
        assertEquals("模型接口返回 HTTP 418", assertThrows(IOException.class,
                () -> client().complete(List.of(new ChatMessage("user", "你好")), List.of())).getMessage());
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
