package dev.huagent.command;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import dev.huagent.app.TeamMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.config.ModelDefaults;
import dev.huagent.goal.GoalStarter;
import dev.huagent.harness.EventHub;
import dev.huagent.harness.HarnessOptions;
import dev.huagent.harness.ModelRetryPolicy;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.mcp.McpManager;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.model.ModelClients;
import dev.huagent.model.OpenAiClient;
import dev.huagent.permission.ConfirmPrompt;
import dev.huagent.prompt.FramedPrompt;
import dev.huagent.prompt.PromptStyle;
import dev.huagent.prompt.TerminalTitle;
import dev.huagent.todo.TodoList;
import org.junit.jupiter.api.Test;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

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
            assertTrue(text.contains("-- provider/after -- 上下文未知 / 10K"), text);
            assertFalse(text.contains("hidden-key"), text);
            assertEquals("before", config.model(), "会话切换不改启动配置");
            // 教程入口没有 ModelDefaults：只在本次会话生效；测试服务器不提供 /models。
            assertTrue(text.contains("模型> 仅本次会话生效。"), text);
            assertTrue(text.contains("模型> 接口没有返回模型列表（模型接口返回 HTTP 404）"), text);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listsEndpointModelsAndSavesOnlyKnownChoicesAsDefault() throws Exception {
        List<String> chatModels = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                chatModels.add(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getString("model"));
                byte[] body = JSON.toJSONBytes(JSONObject.of("choices", List.of(JSONObject.of("message",
                        JSONObject.of("role", "assistant", "content", "收到")))));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.createContext("/v1/models", exchange -> {
            try (exchange) {
                byte[] body = JSON.toJSONBytes(JSONObject.of("data", List.of(JSONObject.of("id", "zeta"),
                        JSONObject.of("id", "Alpha"), JSONObject.of("id", "listed"))));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            String model = "# 模型\nprovider=openai-compatible\nbase-url=" + url
                    + "\nmodel=before\napi-key=hidden-key\nmodels=fav, listed\n";
            Path modelFile = Files.writeString(root.resolve("model.properties"), model, StandardCharsets.UTF_8);
            Path configFile = Files.writeString(root.resolve("config.properties"),
                    "agent.work-dir=" + root.toString().replace('\\', '/') + "\n", StandardCharsets.UTF_8);
            AgentConfig config = AgentConfig.load(configFile, modelFile, Map.of());
            ModelClient client = ModelClients.create(config.modelConfig(), Duration.ofSeconds(5));
            AtomicReference<CommandRegistry> registry = new AtomicReference<>();
            CommandStarter commands = (sources, warnings) -> {
                CommandRegistry bound = CommandStarter.builtin(ModelDefaults.locate(configFile, modelFile, Map.of()))
                        .bind(sources, warnings);
                registry.set(bound);
                return bound;
            };
            BufferedReader input = new BufferedReader(new StringReader(
                    "/model\n/model listed\n/model unknown\n/model fav\n你好\nexit\n"));
            StringWriter buffer = new StringWriter();
            PrintWriter output = new PrintWriter(buffer, true);
            HarnessOptions options = new HarnessOptions(new EventHub(), new ModelRetryPolicy(0), input, false,
                    PromptStyle.plain(), ConfirmPrompt.of(false, false));
            TeamMain.createSession(config, client, input, output, root, List.of(),
                    new HookTrustStore(root.resolve("trust")), new TodoList(), ZoneOffset.UTC, Clock.systemUTC(),
                    false, Duration.ofSeconds(5), McpManager.Starter.none(), options, null, null, commands).run();

            String text = buffer.toString();
            assertTrue(text.contains("模型> 当前模型 before（OpenAI 格式自定义接口）"), text);
            assertTrue(text.contains("模型> 常用 fav、listed"), text);
            assertTrue(text.contains("模型> 接口提供 3 个：Alpha、listed、zeta"), text);
            assertTrue(text.contains("模型> 已从 before 切换到 listed"), text);
            assertTrue(text.contains("模型> 已设为默认，写入 " + modelFile), text);
            assertTrue(text.contains("模型> 接口的模型列表里没有 unknown，仅本次会话生效，未设为默认"), text);
            assertEquals(List.of("fav"), chatModels);
            // 只有 model 一行被改写：最后一次能确认存在的选择是常用模型 fav（接口列表里没有它）。
            assertEquals(model.replace("model=before", "model=fav"),
                    Files.readString(modelFile, StandardCharsets.UTF_8));

            SlashCommand command = registry.get().commands().stream()
                    .filter(each -> each.name().equals("model")).findFirst().orElseThrow();
            assertEquals(List.of(new SlashCommand.Choice("fav", "当前 · 常用"), new SlashCommand.Choice("listed", "常用"),
                    new SlashCommand.Choice("Alpha", ""), new SlashCommand.Choice("zeta", "")),
                    command.choices().get());
            assertFalse(text.contains("hidden-key"), text);
        } finally {
            server.stop(0);
        }
    }
}
