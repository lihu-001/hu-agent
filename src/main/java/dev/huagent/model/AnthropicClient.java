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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
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
    private final boolean stream;

    /**
     * 请求发往 baseUrl + /v1/messages，模型列表取自 baseUrl + /v1/models（baseUrl 已以 /v1 结尾时不再补 /v1）。
     * bearer 为 true 时除 x-api-key 外再发送 Authorization: Bearer，有的兼容接口只认后者；官方接口只用 x-api-key。
     */
    public AnthropicClient(String baseUrl, String apiKey, String model, int maxTokens, boolean bearer,
                           Duration timeout) {
        this(baseUrl, apiKey, model, maxTokens, bearer, timeout, false);
    }

    /**
     * stream 为 true 时流式请求（hu 使用）：回答的文字边生成边交给调用方（见 ModelClient.complete），
     * 两次收到数据之间最多等 timeout；接口不支持流式、返回普通响应时照常解析。其余构造器不流式，与之前相同。
     */
    public AnthropicClient(String baseUrl, String apiKey, String model, int maxTokens, boolean bearer,
                           Duration timeout, boolean stream) {
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
        this.stream = stream;
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
            JSONObject list = ModelHttp.modelList(ModelHttp.send(httpClient, request(uri).GET().build()).body());
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
        return complete(messages, tools, contextTokens, text -> {
        });
    }

    @Override
    public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools, IntConsumer contextTokens,
                                Consumer<String> text) throws IOException, InterruptedException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        String system = system(messages);
        if (!system.isEmpty()) {
            body.put("system", system);
        }
        body.put("messages", serializeMessages(messages));
        if (stream) {
            body.put("stream", true);
        }
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
        HttpRequest request = request(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body), StandardCharsets.UTF_8))
                .build();
        if (!stream) {
            return plainAnswer(ModelHttp.send(httpClient, request).body(), contextTokens);
        }
        StreamedAnswer streamed = new StreamedAnswer(text);
        String plain = ModelHttp.stream(httpClient, request, timeout, streamed);
        if (plain != null) {
            return plainAnswer(plain, contextTokens);
        }
        ChatMessage answer = streamed.answer();
        int tokens = contextTokens(streamed.usage);
        if (tokens >= 0) {
            contextTokens.accept(tokens);
        }
        return answer;
    }

    /** 非流式响应（或接口忽略了 stream）的完整正文。 */
    private static ChatMessage plainAnswer(String body, IntConsumer contextTokens) throws IOException {
        ChatMessage answer = parseAnswer(body);
        int tokens = contextTokens(body);
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
            return contextTokens(JSON.parseObject(body).getJSONObject("usage"));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** 同 contextTokens(body)，usage 是响应里的 usage 对象（流式响应由 message_start 与 message_delta 合成），可以为 null。 */
    static int contextTokens(JSONObject usage) {
        try {
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
            } else {
                // 与官方建议相同，图片块排在文字之前。
                for (ChatImage image : message.images()) {
                    blocks.add(JSONObject.of("type", "image", "source", JSONObject.of("type", "base64",
                            "media_type", image.mediaType(), "data", image.data())));
                }
                if (StrUtil.isNotBlank(message.content())) {
                    blocks.add(JSONObject.of("type", "text", "text", message.content()));
                }
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
            checkFinished(stopReason);
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
            return answer(texts, toolCalls, stopReason);
        } catch (RuntimeException e) {
            throw new IOException("模型响应不是有效的 Messages JSON");
        }
    }

    /** 回答因输出上限被截断（max_tokens）或被拒绝（refusal）时不能当作完整的回答。 */
    private static void checkFinished(String stopReason) throws IOException {
        if ("max_tokens".equals(stopReason) || "refusal".equals(stopReason)) {
            throw new IOException("模型响应未完成：" + stopReason);
        }
    }

    /**
     * 两种响应共用的最后检查：非空白的文本块以空行连接；没有工具调用时 stop_reason 不能是 tool_use，
     * 文字也不能为空（见 EmptyReplyException）。
     */
    private static ChatMessage answer(List<String> texts, List<ToolCall> toolCalls, String stopReason)
            throws IOException {
        String answer = texts.isEmpty() ? null : String.join("\n\n", texts);
        if (toolCalls.isEmpty() && "tool_use".equals(stopReason)) {
            throw new IOException("模型响应缺少文本 content");
        }
        if (toolCalls.isEmpty() && answer == null) {
            throw new EmptyReplyException("模型响应缺少文本 content");
        }
        return ChatMessage.assistant(answer, toolCalls);
    }

    /**
     * 流式响应（message_start、content_block_start / delta / stop、message_delta、message_stop，以及 ping、error）
     * 拼成完整回答。文字增量边收边交给 text：与 parseAnswer 一样，空白的文本块不交出，文本块之间补一个空行；
     * 工具调用的 input 由 input_json_delta 片段拼成。usage 的输入部分来自 message_start，message_delta 更新输出部分。
     */
    private static final class StreamedAnswer implements EventStream.Listener {
        private final Consumer<String> text;
        private final TreeMap<Integer, StreamedBlock> blocks = new TreeMap<>();
        private final JSONObject usage = new JSONObject();
        private String role;
        private String stopReason;
        private boolean stopped;
        /** 已经交出过一个非空白的文本块：下一个文本块之前先交出空行。 */
        private boolean textShown;

        StreamedAnswer(Consumer<String> text) {
            this.text = text;
        }

        @Override
        public boolean event(String type, String data) throws IOException {
            String piece = null;
            try {
                JSONObject event = JSON.parseObject(data);
                if (event == null) {
                    return true;
                }
                switch (StrUtil.blankToDefault(event.getString("type"), type)) {
                    case "error" -> {
                        JSONObject error = event.getJSONObject("error");
                        throw ModelError.streamError(ModelError.ofAnthropicType(
                                error == null ? null : error.getString("type")));
                    }
                    case "message_start" -> {
                        JSONObject message = event.getJSONObject("message");
                        if (message != null) {
                            role = message.getString("role");
                            mergeUsage(message.getJSONObject("usage"));
                        }
                    }
                    case "content_block_start" -> {
                        JSONObject block = event.getJSONObject("content_block");
                        StreamedBlock started = new StreamedBlock(block.getString("type"), block.getString("id"),
                                block.getString("name"), block.get("input"));
                        blocks.put(event.getInteger("index"), started);
                        if ("text".equals(started.type) && block.get("text") instanceof String initial) {
                            piece = appendText(started, initial);
                        }
                    }
                    case "content_block_delta" -> {
                        StreamedBlock block = blocks.get(event.getInteger("index"));
                        JSONObject delta = event.getJSONObject("delta");
                        if (block == null || delta == null) {
                            throw new IOException("模型的流式响应缺少内容块的开始");
                        }
                        if ("text_delta".equals(delta.getString("type")) && "text".equals(block.type)) {
                            if (!(delta.get("text") instanceof String part)) {
                                throw new IOException("模型响应的文本块无效");
                            }
                            piece = appendText(block, part);
                        } else if ("input_json_delta".equals(delta.getString("type"))
                                && delta.get("partial_json") instanceof String part) {
                            block.json.append(part);
                        }
                        // thinking_delta、signature_delta 等不进入历史。
                    }
                    case "message_delta" -> {
                        JSONObject delta = event.getJSONObject("delta");
                        if (delta != null && delta.getString("stop_reason") != null) {
                            stopReason = delta.getString("stop_reason");
                        }
                        mergeUsage(event.getJSONObject("usage"));
                    }
                    case "message_stop" -> {
                        stopped = true;
                        return false;
                    }
                    default -> {
                        // ping 与新增的事件类型。
                    }
                }
            } catch (RuntimeException e) {
                throw new IOException("模型的流式响应不是有效的 Messages JSON");
            }
            if (piece != null) {
                text.accept(piece);
            }
            return true;
        }

        /** 记下文字，返回要交出的部分：块的开头是空白时先留着，等出现非空白字符再一起交出。 */
        private String appendText(StreamedBlock block, String part) {
            block.text.append(part);
            if (block.shown) {
                return part.isEmpty() ? null : part;
            }
            if (block.text.toString().isBlank()) {
                return null;
            }
            String piece = (textShown ? "\n\n" : "") + block.text;
            block.shown = true;
            textShown = true;
            return piece;
        }

        /** 只合并有值的字段：message_delta 的 usage 可能只有 output_tokens。 */
        private void mergeUsage(JSONObject part) {
            if (part != null) {
                part.forEach((key, value) -> {
                    if (value != null) {
                        usage.put(key, value);
                    }
                });
            }
        }

        /** 事件流结束后的完整回答；既没有 message_stop 也没有 stop_reason 时回答可能不完整。 */
        ChatMessage answer() throws IOException {
            if (!stopped && stopReason == null) {
                throw ModelError.streamIncomplete();
            }
            if (role != null && !"assistant".equals(role)) {
                throw new IOException("模型响应缺少 assistant 消息");
            }
            checkFinished(stopReason);
            List<String> texts = new ArrayList<>();
            List<ToolCall> toolCalls = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (StreamedBlock block : blocks.values()) {
                if ("text".equals(block.type) && !block.text.toString().isBlank()) {
                    texts.add(block.text.toString());
                } else if ("tool_use".equals(block.type)) {
                    JSONObject input = block.input();
                    if (StrUtil.isBlank(block.id) || !ids.add(block.id) || StrUtil.isBlank(block.name)
                            || input == null) {
                        throw new IOException("模型响应包含无效或重复的工具调用");
                    }
                    toolCalls.add(new ToolCall(block.id, block.name, input.toJSONString()));
                }
            }
            return AnthropicClient.answer(texts, toolCalls, stopReason);
        }
    }

    private static final class StreamedBlock {
        private final String type;
        private final String id;
        private final String name;
        /** content_block_start 里的 input，通常是空对象；之后的参数在 json 片段里。 */
        private final Object initialInput;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder json = new StringBuilder();
        private boolean shown;

        StreamedBlock(String type, String id, String name, Object initialInput) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.initialInput = initialInput;
        }

        /** 工具调用的参数：没有 json 片段时用开始时的 input；拼出的不是 JSON 对象时返回 null。 */
        JSONObject input() {
            if (json.isEmpty()) {
                return initialInput instanceof JSONObject input ? input : null;
            }
            try {
                return JSON.parseObject(json.toString());
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
