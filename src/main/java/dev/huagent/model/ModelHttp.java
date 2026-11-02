package dev.huagent.model;

import dev.huagent.config.ModelConfig;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OpenAI 与 Anthropic 两种协议的客户端共用：基础地址与 api-key 校验、发送请求（失败分类见 ModelError）与流式请求、
 * 模型列表解析。
 */
final class ModelHttp {
    /** 建立连接最多等这么久：请求超时可以很长（模型生成慢），连不上的地址仍应很快报错。 */
    static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private ModelHttp() {
    }

    /** 两种协议的客户端共用的 HttpClient：连接超时取请求超时与 MAX_CONNECT_TIMEOUT 中较小的。 */
    static HttpClient httpClient(Duration timeout) {
        Duration connect = timeout.compareTo(MAX_CONNECT_TIMEOUT) < 0 ? timeout : MAX_CONNECT_TIMEOUT;
        return HttpClient.newBuilder().connectTimeout(connect).build();
    }

    /** 发送请求，只返回成功（2xx）的响应；网络失败与错误状态码按 ModelError 分类后抛出。 */
    static HttpResponse<String> send(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw ModelError.connectionFailure(e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw ModelError.failure(response);
        }
        return response;
    }

    /**
     * 发送流式请求：2xx 且是事件流（text/event-stream）时逐个事件交给 events（见 EventStream），返回 null；
     * 接口不支持流式、返回了普通响应时返回完整正文，由调用方按非流式响应解析。失败的分类与 send 相同。
     */
    static String stream(HttpClient client, HttpRequest request, Duration idleTimeout, EventStream.Listener events)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw ModelError.connectionFailure(e);
        }
        // 关闭连接：中断、超时或出错时不再接收剩下的响应。
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw ModelError.failure(status, response.headers(), readAll(body));
            }
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (!type.toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
                return readAll(body);
            }
            EventStream.read(body, idleTimeout, events);
            return null;
        }
    }

    /** 读出整个正文；读取中被中断时抛出 InterruptedException，连接断开按连接失败处理。 */
    private static String readAll(InputStream body) throws IOException, InterruptedException {
        try {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (Thread.interrupted()) {
                throw new InterruptedException("模型请求被中断");
            }
            throw ModelError.connectionFailure(e);
        }
    }

    /** 模型列表响应正文：两种协议都是 {"data": [{"id": …}, …]}，Anthropic 另有分页字段。 */
    static JSONObject modelList(String body) throws IOException {
        try {
            JSONObject json = JSON.parseObject(body);
            if (json == null || !(json.get("data") instanceof JSONArray)) {
                throw new IOException("模型列表响应缺少 data");
            }
            return json;
        } catch (RuntimeException e) {
            throw new IOException("模型列表响应不是有效的 JSON");
        }
    }

    /** data 中的模型名称；名称不合法或不是文本的条目跳过。 */
    static List<String> modelIds(JSONObject list) {
        List<String> ids = new ArrayList<>();
        for (Object item : list.getJSONArray("data")) {
            if (item instanceof JSONObject model && model.get("id") instanceof String id) {
                try {
                    ids.add(ModelConfig.checkedModel(id));
                } catch (IllegalArgumentException e) {
                    // 无法通过 /model 选中的名称不列出。
                }
            }
        }
        return ids;
    }

    /** 返回去掉末尾斜杠的基础地址，客户端在后面拼接各自的接口路径。 */
    static String baseUrl(String baseUrl, String apiKey, String model) {
        if (StrUtil.isBlank(baseUrl) || StrUtil.isBlank(apiKey) || StrUtil.isBlank(model)) {
            throw new IllegalArgumentException("base-url、api-key 和 model 不能为空");
        }
        URI base;
        try {
            base = URI.create(baseUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("base-url 格式无效");
        }
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("base-url 必须是 HTTP(S) 基础地址，不含认证信息、查询参数或片段");
        }
        return base.toString().replaceAll("/+$", "");
    }

    static String apiKey(String apiKey) {
        String key = apiKey.strip();
        if (key.contains("\r") || key.contains("\n")) {
            throw new IllegalArgumentException("api-key 不能包含换行");
        }
        return key;
    }

}
