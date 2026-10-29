package dev.huagent.model;

import dev.huagent.config.ModelConfig;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Anthropic Messages 协议（官方或兼容接口）。对话历史保持 Chat Completions 的形状，发送前在这里转换：
 * system 消息合并为顶层 system，助手的工具调用变成 tool_use 块，工具结果变成用户消息中的 tool_result 块，
 * 相邻的同角色消息合并为一条（协议要求 user 与 assistant 交替）。
 */
public final class AnthropicClient implements ModelClient, ModelSelection {
    public static final String API_VERSION = "2023-06-01";
    /** 模型列表每页的条数（协议上限）与最多读取的页数。 */
    static final int MODELS_PAGE_SIZE = 1000;
    static final int MODELS_MAX_PAGES = 10;

    private final HttpClient httpClient;
    private final URI endpoint;
    private final String modelsEndpoint;
    private final String apiKey;
    private volatile String model;
    private final int maxTokens;
    private final boolean bearer;
    private final Duration timeout;

    /**
     * 请求发往 baseUrl + /v1/messages，模型列表取自 baseUrl + /v1/models（baseUrl 已以 /v1 结尾时不再补 /v1）。
     * bearer 为 true 时除 x-api-key 外再发送 Authorization: Bearer，有的兼容接口只认后者；官方接口只用 x-api-key。
     */
    public AnthropicClient(String baseUrl, String apiKey, String model, int maxTokens, boolean bearer,
                           Duration timeout) {
        String base = ModelHttp.baseUrl(baseUrl, apiKey, model);
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("max-output-tokens 必须为正整数");
        }
        String versioned = base.endsWith("/v1") ? base : base + "/v1";
        this.endpoint = URI.create(versioned + "/messages");
        this.modelsEndpoint = versioned + "/models";
        this.apiKey = ModelHttp.apiKey(apiKey);
        this.model = model.strip();
        this.maxTokens = maxTokens;
        this.bearer = bearer;
        this.timeout = timeout;
        this.httpClient = ModelHttp.httpClient(timeout);
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public void selectModel(String name) {
        model = ModelConfig.checkedModel(name);
    }

    /** 按 has_more / last_id 翻页，最多 MODELS_MAX_PAGES 页。 */
    @Override
    public List<String> listModels() throws IOException, InterruptedException {
        List<String> models = new ArrayList<>();
        String after = null;
        for (int page = 0; page < MODELS_MAX_PAGES; page++) {
            URI uri = URI.create(modelsEndpoint + "?limit=" + MODELS_PAGE_SIZE
                    + (after == null ? "" : "&after_id=" + URLEncoder.encode(after, StandardCharsets.UTF_8)));
            JSONObject list = ModelHttp.modelList(httpClient.send(request(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
            models.addAll(ModelHttp.modelIds(list));
            after = list.getString("last_id");
            if (!Boolean.TRUE.equals(list.getBoolean("has_more")) || StrUtil.isBlank(after)) {
                break;
            }
        }
        return models;
    }

    @Override
    public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools)
            throws IOException, InterruptedException {
        return complete(messages, tools, tokens -> {
        });
    }

    @Override
    public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools, IntConsumer contextTokens)
            throws IOException, InterruptedException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        String system = system(messages);
        if (!system.isEmpty()) {
            body.put("system", system);
        }
        body.put("messages", serializeMessages(messages));
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(tool -> {
                JSONObject definition = new JSONObject();
                definition.put("name", tool.name());
                definition.put("description", tool.description());
                definition.put("input_schema", tool.parameters());
                return definition;
            }).toList());
            body.put("tool_choice", JSONObject.of("type", "auto"));
        }
        HttpResponse<String> response = httpClient.send(request(endpoint)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw ModelHttp.failure(response);
        }
        ChatMessage answer = parseAnswer(response.body());
        int tokens = contextTokens(response.body());
        if (tokens >= 0) {
            contextTokens.accept(tokens);
        }
        return answer;
    }

    /** 两种请求共用的鉴权与版本头。 */
    private HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION);
        if (bearer) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        return request;
    }

    /**
     * 回答进入历史后对话占用的 token：输入（含缓存读写的部分）加输出。
     * usage 缺失或格式不对时返回 -1，不影响回答本身。
     */
    static int contextTokens(String body) {
        try {
            JSONObject usage = JSON.parseObject(body).getJSONObject("usage");
            Integer input = usage == null ? null : usage.getInteger("input_tokens");
            if (input == null || input < 0) {
                return -1;
            }
            long total = (long) input + Math.max(0, usage.getIntValue("cache_creation_input_tokens"))
                    + Math.max(0, usage.getIntValue("cache_read_input_tokens"))
                    + Math.max(0, usage.getIntValue("output_tokens"));
            return (int) Math.min(Integer.MAX_VALUE, total);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** 协议没有 system 角色，全部 system 消息按顺序合并为顶层 system。 */
    private static String system(List<ChatMessage> messages) {
        return String.join("\n\n", messages.stream()
                .filter(message -> "system".equals(message.role()) && StrUtil.isNotBlank(message.content()))
                .map(ChatMessage::content).toList());
    }

    /** 协议不接受空白文本块，空白内容不发送；转换后没有内容块的消息整条省略。 */
    static JSONArray serializeMessages(List<ChatMessage> messages) {
        JSONArray result = new JSONArray();
        for (ChatMessage message : messages) {
            if ("system".equals(message.role())) {
                continue;
            }
            boolean assistant = "assistant".equals(message.role());
            JSONArray blocks = new JSONArray();
            if ("tool".equals(message.role())) {
                JSONObject toolResult = JSONObject.of("type", "tool_result", "tool_use_id", message.toolCallId());
                if (StrUtil.isNotEmpty(message.content())) {
                    toolResult.put("content", message.content());
                }
                blocks.add(toolResult);
            } else if (StrUtil.isNotBlank(message.content())) {
                blocks.add(JSONObject.of("type", "text", "text", message.content()));
            }
            if (assistant) {
                for (ToolCall call : message.toolCalls()) {
                    blocks.add(JSONObject.of("type", "tool_use", "id", call.id(), "name", call.name(),
                            "input", toolInput(call.arguments())));
                }
            }
            if (blocks.isEmpty()) {
                continue;
            }
            String role = assistant ? "assistant" : "user";
            JSONObject last = result.isEmpty() ? null : result.getJSONObject(result.size() - 1);
            if (last != null && role.equals(last.getString("role"))) {
                last.getJSONArray("content").addAll(blocks);
            } else {
                result.add(JSONObject.of("role", role, "content", blocks));
            }
        }
        return result;
    }

    /** 协议要求 input 是 JSON 对象；历史中无法解析的参数（工具执行时已报错给模型）以空对象发送。 */
    private static JSONObject toolInput(String arguments) {
        try {
            JSONObject input = JSON.parseObject(arguments);
            return input == null ? new JSONObject() : input;
        } catch (RuntimeException e) {
            return new JSONObject();
        }
    }

    private static ChatMessage parseAnswer(String body) throws IOException {
        try {
            JSONObject response = JSON.parseObject(body);
            if (response == null || !"assistant".equals(response.getString("role"))) {
                throw new IOException("模型响应缺少 assistant 消息");
            }
            String stopReason = response.getString("stop_reason");
            if ("max_tokens".equals(stopReason) || "refusal".equals(stopReason)) {
                throw new IOException("模型响应未完成：" + stopReason);
            }
            JSONArray content = response.getJSONArray("content");
            if (content == null) {
                throw new IOException("模型响应缺少 content");
            }
            List<String> texts = new ArrayList<>();
            List<ToolCall> toolCalls = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < content.size(); i++) {
                JSONObject block = content.getJSONObject(i);
                String type = block.getString("type");
                if ("text".equals(type)) {
                    if (!(block.get("text") instanceof String text)) {
                        throw new IOException("模型响应的文本块无效");
                    }
                    if (!text.isBlank()) {
                        texts.add(text);
                    }
                } else if ("tool_use".equals(type)) {
                    String id = block.getString("id");
                    if (StrUtil.isBlank(id) || !ids.add(id) || StrUtil.isBlank(block.getString("name"))
                            || !(block.get("input") instanceof JSONObject input)) {
                        throw new IOException("模型响应包含无效或重复的工具调用");
                    }
                    toolCalls.add(new ToolCall(id, block.getString("name"), input.toJSONString()));
                }
                // thinking 等其他内容块不进入历史。
            }
            String answer = texts.isEmpty() ? null : String.join("\n\n", texts);
            if (toolCalls.isEmpty() && (answer == null || "tool_use".equals(stopReason))) {
                throw new IOException("模型响应缺少文本 content");
            }
            return ChatMessage.assistant(answer, toolCalls);
        } catch (RuntimeException e) {
            throw new IOException("模型响应不是有效的 Messages JSON");
        }
    }
}
