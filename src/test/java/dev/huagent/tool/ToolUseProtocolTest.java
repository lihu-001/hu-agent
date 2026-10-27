package dev.huagent.tool;

import dev.huagent.app.ToolUseMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.loop.ChatLoop;
import dev.huagent.model.OpenAiClient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ToolUseProtocolTest {
    @TempDir
    Path root;

    @Test
    void sendsFiveSchemasAndReturnsFileResultsThroughRealHttpClient() throws Exception {
        List<JSONObject> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                requests.add(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                Object message = requests.size() == 1
                        ? Map.of("role", "assistant", "tool_calls", List.of(
                                wireCall("w", "write_file", Map.of("path", "note.txt", "content", "中文初稿")),
                                wireCall("e", "edit_file", Map.of("path", "note.txt", "old_text", "初稿", "new_text", "终稿")),
                                wireCall("r", "read_file", Map.of("path", "note.txt")),
                                wireCall("g", "glob", Map.of("pattern", "**/*.txt"))))
                        : Map.of("role", "assistant", "content", "已验证终稿");
                byte[] body = JSON.toJSONBytes(Map.of("choices", List.of(Map.of("message", message))));
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            AgentConfig config = new AgentConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-key", "fake", root, 10, Duration.ofSeconds(5), 12000);
            StringWriter output = new StringWriter();
            new ChatLoop(new OpenAiClient(config.baseUrl(), config.apiKey(), config.model(), Duration.ofSeconds(5)),
                    ToolUseMain.createTools(config), 10, ToolUseMain.SYSTEM_PROMPT)
                    .run(new BufferedReader(new StringReader("执行\nexit\n")),
                    new PrintWriter(output));
            assertEquals(2, requests.size());
            JSONArray schemas = requests.get(0).getJSONArray("tools");
            assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob"),
                    schemas.stream().map(value -> ((JSONObject) value).getJSONObject("function").getString("name")).toList());
            for (int i = 0; i < schemas.size(); i++) {
                assertEquals("function", schemas.getJSONObject(i).getString("type"));
                JSONObject parameters = schemas.getJSONObject(i).getJSONObject("function").getJSONObject("parameters");
                assertEquals("object", parameters.getString("type"));
                assertFalse(parameters.getJSONArray("required").isEmpty());
            }
            JSONArray messages = requests.get(1).getJSONArray("messages");
            for (int i = 0; i < 4; i++) {
                assertEquals("tool", messages.getJSONObject(i + 3).getString("role"));
                assertEquals(List.of("w", "e", "r", "g").get(i), messages.getJSONObject(i + 3).getString("tool_call_id"));
            }
            assertEquals("中文终稿", messages.getJSONObject(5).getString("content"));
            assertEquals("note.txt", messages.getJSONObject(6).getString("content"));
            assertEquals("中文终稿", Files.readString(root.resolve("note.txt")));
            assertTrue(output.toString().contains("助手> 已验证终稿"));
        } finally {
            server.stop(0);
        }
    }

    private Map<String, Object> wireCall(String id, String name, Map<String, Object> args) {
        return Map.of("id", id, "type", "function",
                "function", Map.of("name", name, "arguments", JSON.toJSONString(args)));
    }
}
