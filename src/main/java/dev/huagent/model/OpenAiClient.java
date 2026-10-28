package dev.huagent.model;

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
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

public final class OpenAiClient implements ModelClient, ModelSelection {
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String apiKey;
    private volatile String model;
    private final Duration timeout;

    public OpenAiClient(String baseUrl, String apiKey, String model, Duration timeout) {
        this.endpoint = URI.create(ModelHttp.baseUrl(baseUrl, apiKey, model) + "/chat/completions");
        this.apiKey = ModelHttp.apiKey(apiKey);
        this.model = model.strip();
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public void selectModel(String name) {
        model = ModelSelection.checkedModel(name);
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
        body.put("messages", messages.stream().map(this::serializeMessage).toList());
        body.put("stream", false);
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
        HttpResponse<String> response = httpClient.send(request,
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

    /**
     * 回答进入历史后对话占用的 token：输入加输出，减去不进入历史的推理 token。
     * usage 是可选字段，缺失或格式不对时返回 -1，不影响回答本身。
     */
    static int contextTokens(String body) {
        try {
            JSONObject usage = JSON.parseObject(body).getJSONObject("usage");
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
        if (message.content() != null) {
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

    private ChatMessage parseAnswer(String body) throws IOException {
        try {
            JSONObject response = JSON.parseObject(body);
            JSONArray choices = response == null ? null : response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("模型响应缺少 choices");
            }
            JSONObject choice = choices.getJSONObject(0);
            String finishReason = choice.getString("finish_reason");
            if ("length".equals(finishReason) || "content_filter".equals(finishReason)) {
                throw new IOException("模型响应未完成：" + finishReason);
            }
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
            String answer = (String) content;
            if (toolCalls.isEmpty() && (StrUtil.isBlank(answer) || "tool_calls".equals(finishReason))) {
                throw new IOException("模型响应缺少文本 content");
            }
            return ChatMessage.assistant(answer, toolCalls);
        } catch (RuntimeException e) {
            throw new IOException("模型响应不是有效的 Chat Completions JSON");
        }
    }
}
