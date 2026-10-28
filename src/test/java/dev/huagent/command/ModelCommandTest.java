package dev.huagent.command;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import dev.huagent.app.TeamMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.goal.GoalStarter;
import dev.huagent.harness.EventHub;
import dev.huagent.harness.HarnessOptions;
import dev.huagent.harness.ModelRetryPolicy;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.mcp.McpManager;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.model.OpenAiClient;
import dev.huagent.permission.ConfirmPrompt;
import dev.huagent.prompt.FramedPrompt;
import dev.huagent.prompt.TerminalTitle;
import dev.huagent.todo.TodoList;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ModelCommandTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void switchesRequestsWithoutLosingHistoryAndKeepsIndependentJudge(boolean team, boolean independentJudge)
            throws Exception {
        List<JSONObject> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                JSONObject request = JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                requests.add(request);
                String answer = request.containsKey("tools") ? "收到"
                        : "{\"ok\":true,\"reason\":\"完成\",\"impossible\":false}";
                byte[] body = JSON.toJSONBytes(JSONObject.of("choices", List.of(JSONObject.of("message",
                        JSONObject.of("role", "assistant", "content", answer))),
                        "usage", JSONObject.of("prompt_tokens", 1200, "completion_tokens", 80)));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            AgentConfig config = new AgentConfig(url, "hidden-key", "before", root, 6,
                    Duration.ofSeconds(5), 12000, 50000, 3, Duration.ofSeconds(10), 15, team, 8,
                    independentJudge ? "judge" : null);
            ModelClient client = new OpenAiClient(url, config.apiKey(), config.model(), Duration.ofSeconds(5));
            ModelClient judge = independentJudge
                    ? new OpenAiClient(url, config.apiKey(), "judge", Duration.ofSeconds(5)) : client;
            List<String> prompts = new ArrayList<>();
            AgentHook hook = new AgentHook() {
                @Override
                public String userPromptSubmit(String prompt, List<ChatMessage> history) {
                    prompts.add(prompt);
                    return null;
                }
            };
            BufferedReader input = new BufferedReader(new StringReader("第一轮\n/model\n/model provider/after\n"
                    + "/model\n/model bad name\n/model provider/after\n/status\n/goal 完成\nexit\n"));
            StringWriter buffer = new StringWriter();
            PrintWriter output = new PrintWriter(buffer, true);
            FramedPrompt framed = new FramedPrompt("> ", '-', () -> 140, "before", 10000);
            HarnessOptions options = new HarnessOptions(new EventHub(), new ModelRetryPolicy(0), input, false,
                    new TerminalTitle(ignored -> {}, root).prompt(framed), ConfirmPrompt.of(false, false));
            TeamMain.Session session = TeamMain.createSession(config, client, input, output, root, List.of(hook),
                    new HookTrustStore(root.resolve("trust")), new TodoList(), ZoneOffset.UTC, Clock.systemUTC(),
                    false, Duration.ofSeconds(5), McpManager.Starter.none(), options, null,
                    GoalStarter.of(judge, 8), CommandStarter.builtin());
            session.run();

            assertEquals(List.of("before", "provider/after", independentJudge ? "judge" : "provider/after"),
                    requests.stream().map(r -> r.getString("model")).toList());
            assertEquals(List.of("system", "user", "assistant", "user"), requests.get(1).getJSONArray("messages")
                    .toJavaList(JSONObject.class).stream().map(m -> m.getString("role")).toList());
            assertEquals("第一轮", requests.get(1).getJSONArray("messages").getJSONObject(1).getString("content"));
            assertEquals(List.of("第一轮", dev.huagent.goal.GoalController.taskPrompt("完成")), prompts);
            String text = buffer.toString();
            assertTrue(text.contains("模型> 当前模型 provider/after"), text);
            assertTrue(text.contains("状态> 模型 provider/after，目标判断器 "
                    + (independentJudge ? "judge" : "provider/after")), text);
            // 切换后、下一次请求前，旧模型的 usage 被清除，显示新模型与最大上下文。
            assertTrue(text.contains("-- provider/after -- 上下文最大 10K token"), text);
            assertFalse(text.contains("hidden-key"), text);
            assertEquals("before", config.model(), "会话切换不改启动配置");
        } finally {
            server.stop(0);
        }
    }
}
