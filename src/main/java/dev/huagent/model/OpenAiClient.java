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

public final class OpenAiClient implements ModelClient, ModelSelection {
    private final HttpClient httpClient;
    private final URI endpoint;
    private final URI modelsEndpoint;
    private final String apiKey;
    private volatile String model;
    private final Duration timeout;
    private final boolean stream;

    public OpenAiClient(String baseUrl, String apiKey, String model, Duration timeout) {
        this(baseUrl, apiKey, model, timeout, false);
    }

    /**
     * stream 为 true 时流式请求（hu 使用）：回答的文字边生成边交给调用方（见 ModelClient.complete），
     * 两次收到数据之间最多等 timeout；接口不支持流式、返回普通响应时照常解析。其余构造器不流式，与之前相同。
     */
    public OpenAiClient(String baseUrl, String apiKey, String model, Duration timeout, boolean stream) {
        String base = ModelHttp.baseUrl(baseUrl, apiKey, model);
        this.endpoint = URI.create(base + "/chat/completions");
        this.modelsEndpoint = URI.create(base + "/models");
        this.apiKey = ModelHttp.apiKey(apiKey);
        this.model = model.strip();
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

    @Override
    public List<String> listModels() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(modelsEndpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        return ModelHttp.modelIds(ModelHttp.modelList(ModelHttp.send(httpClient, request).body()));
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
        body.put("messages", messages.stream().map(this::serializeMessage).toList());
        body.put("stream", stream);
        if (stream) {
            // 流式响应默认不带 usage，要求接口在最后一个事件里给出（状态栏的上下文用量要用）。
            body.put("stream_options", JSONObject.of("include_usage", true));
        }
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(tool -> {
                JSONObject function = new JSONObject();
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.put("parameters", tool.parameters());
                JSONObject definition = new JSONObject();
                definition.put("type", "function");
                definition.put("function", function);
                return definition;
            }).toList());
            body.put("tool_choice", "auto");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
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

    /**
     * 回答进入历史后对话占用的 token：输入加输出，减去不进入历史的推理 token。
     * usage 是可选字段，缺失或格式不对时返回 -1，不影响回答本身。
     */
    static int contextTokens(String body) {
        try {
            return contextTokens(JSON.parseObject(body).getJSONObject("usage"));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** 同 contextTokens(body)，usage 是响应里的 usage 对象（流式响应在最后一个事件里），可以为 null。 */
    static int contextTokens(JSONObject usage) {
        try {
            Integer prompt = usage == null ? null : usage.getInteger("prompt_tokens");
            if (prompt == null || prompt < 0) {
                return -1;
            }
            int completion = Math.max(0, usage.getIntValue("completion_tokens"));
            JSONObject details = usage.getJSONObject("completion_tokens_details");
            int reasoning = details == null ? 0 : Math.max(0, details.getIntValue("reasoning_tokens"));
            return (int) Math.min(Integer.MAX_VALUE, (long) prompt + Math.max(0, completion - reasoning));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private JSONObject serializeMessage(ChatMessage message) {
        JSONObject json = new JSONObject();
        json.put("role", message.role());
        if (!message.images().isEmpty()) {
            // 带图片的用户消息：content 改为内容片段数组，文字在前、图片以 data URL 发送。
            JSONArray parts = new JSONArray();
            if (StrUtil.isNotBlank(message.content())) {
                parts.add(JSONObject.of("type", "text", "text", message.content()));
            }
            for (ChatImage image : message.images()) {
                parts.add(JSONObject.of("type", "image_url", "image_url", JSONObject.of("url", image.dataUrl())));
            }
            json.put("content", parts);
        } else if (message.content() != null) {
            json.put("content", message.content());
        }
        if (message.toolCallId() != null) {
            json.put("tool_call_id", message.toolCallId());
        }
        if (!message.toolCalls().isEmpty()) {
            json.put("tool_calls", message.toolCalls().stream().map(call -> {
                JSONObject function = new JSONObject();
                function.put("name", call.name());
                function.put("arguments", call.arguments());
                JSONObject toolCall = new JSONObject();
                toolCall.put("id", call.id());
                toolCall.put("type", "function");
                toolCall.put("function", function);
                return toolCall;
            }).toList());
        }
        return json;
    }

    private static ChatMessage parseAnswer(String body) throws IOException {
        try {
            JSONObject response = JSON.parseObject(body);
            JSONArray choices = response == null ? null : response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("模型响应缺少 choices");
            }
            JSONObject choice = choices.getJSONObject(0);
            String finishReason = choice.getString("finish_reason");
            checkFinished(finishReason);
            JSONObject message = choice.getJSONObject("message");
            if (message == null || !"assistant".equals(message.getString("role"))) {
                throw new IOException("模型响应缺少 assistant 消息");
            }
            JSONArray calls = message.getJSONArray("tool_calls");
            if (message.get("function_call") != null) {
                throw new IOException("不支持旧版 function_call，请使用 tool_calls");
            }
            Object content = message.get("content");
            if (content != null && !(content instanceof String)) {
                throw new IOException("模型响应的 content 必须是文本或 null");
            }
            List<ToolCall> toolCalls = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            if (calls != null) {
                for (int i = 0; i < calls.size(); i++) {
                    JSONObject call = calls.getJSONObject(i);
                    JSONObject function = call.getJSONObject("function");
                    String id = call.getString("id");
                    if (!"function".equals(call.getString("type")) || StrUtil.isBlank(id)
                            || !ids.add(id) || function == null
                            || StrUtil.isBlank(function.getString("name"))
                            || !(function.get("arguments") instanceof String)) {
                        throw new IOException("模型响应包含无效或重复的工具调用");
                    }
                    // arguments 的 JSON 校验由工具执行器负责，错误可回传模型修正。
                    toolCalls.add(new ToolCall(id, function.getString("name"), function.getString("arguments")));
                }
            }
            return answer((String) content, toolCalls, finishReason);
        } catch (RuntimeException e) {
            throw new IOException("模型响应不是有效的 Chat Completions JSON");
        }
    }

    /** 回答被截断（length）或被过滤（content_filter）时不能当作完整的回答。 */
    private static void checkFinished(String finishReason) throws IOException {
        if ("length".equals(finishReason) || "content_filter".equals(finishReason)) {
            throw new IOException("模型响应未完成：" + finishReason);
        }
    }

    /** 两种响应共用的最后检查：没有工具调用时 finish_reason 不能是 tool_calls，文字也不能为空（见 EmptyReplyException）。 */
    private static ChatMessage answer(String content, List<ToolCall> toolCalls, String finishReason) throws IOException {
        if (toolCalls.isEmpty() && "tool_calls".equals(finishReason)) {
            throw new IOException("模型响应缺少文本 content");
        }
        if (toolCalls.isEmpty() && StrUtil.isBlank(content)) {
            throw new EmptyReplyException("模型响应缺少文本 content");
        }
        return ChatMessage.assistant(content, toolCalls);
    }

    /**
     * 流式响应（chat.completion.chunk 事件，以 data: [DONE] 结束）拼成完整回答：文字增量边收边交给 text，
     * 工具调用按 index 拼接，id 与名称取第一次出现的，参数片段依次连接；usage 在最后一个事件里。
     * 只看第一个回答（index 0），推理内容（reasoning_content 等）与非流式一样不进入历史。
     */
    private static final class StreamedAnswer implements EventStream.Listener {
        private final Consumer<String> text;
        private StringBuilder content;
        private final TreeMap<Integer, StreamedCall> calls = new TreeMap<>();
        private String finishReason;
        private JSONObject usage;
        private boolean done;

        StreamedAnswer(Consumer<String> text) {
            this.text = text;
        }

        @Override
        public boolean event(String type, String data) throws IOException {
            if ("[DONE]".equals(data.strip())) {
                done = true;
                return false;
            }
            StringBuilder pieces = new StringBuilder();
            try {
                JSONObject chunk = JSON.parseObject(data);
                if (chunk == null) {
                    return true;
                }
                if (chunk.get("error") instanceof JSONObject error) {
                    throw ModelError.streamError(error.get("code") instanceof Number code
                            ? ModelError.of(code.intValue()) : null);
                }
                if (chunk.getJSONObject("usage") != null) {
                    usage = chunk.getJSONObject("usage");
                }
                JSONArray choices = chunk.getJSONArray("choices");
                for (int i = 0; choices != null && i < choices.size(); i++) {
                    JSONObject choice = choices.getJSONObject(i);
                    if (choice.getIntValue("index") != 0) {
                        continue;
                    }
                    if (choice.getString("finish_reason") != null) {
                        finishReason = choice.getString("finish_reason");
                    }
                    JSONObject delta = choice.getJSONObject("delta");
                    if (delta == null) {
                        continue;
                    }
                    if (delta.get("function_call") != null) {
                        throw new IOException("不支持旧版 function_call，请使用 tool_calls");
                    }
                    Object piece = delta.get("content");
                    if (piece != null && !(piece instanceof String)) {
                        throw new IOException("模型响应的 content 必须是文本或 null");
                    }
                    JSONArray deltaCalls = delta.getJSONArray("tool_calls");
                    for (int j = 0; deltaCalls != null && j < deltaCalls.size(); j++) {
                        merge(deltaCalls.getJSONObject(j));
                    }
                    if (piece instanceof String part && !part.isEmpty()) {
                        content = content == null ? new StringBuilder(part) : content.append(part);
                        pieces.append(part);
                    }
                }
            } catch (RuntimeException e) {
                throw new IOException("模型的流式响应不是有效的 Chat Completions JSON");
            }
            if (!pieces.isEmpty()) {
                text.accept(pieces.toString());
            }
            return true;
        }

        /** 有 index 时按 index 归并；个别接口不给 index，这时新的 id 开始一个新调用，否则接着上一个。 */
        private void merge(JSONObject delta) throws IOException {
            Integer index = delta.getInteger("index");
            String id = delta.getString("id");
            StreamedCall call;
            if (index != null) {
                call = calls.computeIfAbsent(index, key -> new StreamedCall());
            } else if (calls.isEmpty() || StrUtil.isNotBlank(id) && !id.equals(calls.lastEntry().getValue().id)) {
                call = new StreamedCall();
                calls.put(calls.isEmpty() ? 0 : calls.lastKey() + 1, call);
            } else {
                call = calls.lastEntry().getValue();
            }
            if (StrUtil.isBlank(call.id) && StrUtil.isNotBlank(id)) {
                call.id = id;
            }
            JSONObject function = delta.getJSONObject("function");
            if (function == null) {
                return;
            }
            if (StrUtil.isBlank(call.name) && StrUtil.isNotBlank(function.getString("name"))) {
                call.name = function.getString("name");
            }
            Object arguments = function.get("arguments");
            if (arguments instanceof String part) {
                call.arguments.append(part);
            } else if (arguments != null) {
                throw new IOException("模型响应包含无效或重复的工具调用");
            }
        }

        /** 事件流结束后的完整回答；既没有 [DONE] 也没有 finish_reason 时回答可能不完整。 */
        ChatMessage answer() throws IOException {
            if (!done && finishReason == null) {
                throw ModelError.streamIncomplete();
            }
            checkFinished(finishReason);
            List<ToolCall> toolCalls = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (StreamedCall call : calls.values()) {
                if (StrUtil.isBlank(call.id) || !ids.add(call.id) || StrUtil.isBlank(call.name)) {
                    throw new IOException("模型响应包含无效或重复的工具调用");
                }
                // 不需要参数的工具可能一个参数片段也没有，按空对象记录。
                toolCalls.add(new ToolCall(call.id, call.name,
                        call.arguments.isEmpty() ? "{}" : call.arguments.toString()));
            }
            return OpenAiClient.answer(content == null ? null : content.toString(), toolCalls, finishReason);
        }
    }

    private static final class StreamedCall {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
