package dev.huagent.model;

import dev.huagent.config.ModelConfig;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** OpenAI 与 Anthropic 两种协议的客户端共用：基础地址与 api-key 校验、HTTP 失败分类、模型列表解析。 */
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

    /** 模型列表响应：两种协议都是 {"data": [{"id": …}, …]}，Anthropic 另有分页字段。 */
    static JSONObject modelList(HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw failure(response);
        }
        try {
            JSONObject json = JSON.parseObject(response.body());
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

    /** S15：把可恢复的失败分成限流与上下文超长两类，其余保持通用失败。正文可能含敏感信息，不进入异常消息。 */
    static IOException failure(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 429 || status == 503 || status == 529) {
            return new ModelUnavailableException("模型接口返回 HTTP " + status
                    + (status == 429 ? "（限流）" : "（服务暂不可用）"), retryAfterSeconds(response));
        }
        if ((status == 400 || status == 413) && looksLikeContextOverflow(response.body())) {
            return new ContextTooLargeException("模型接口返回 HTTP " + status + "（上下文超长）");
        }
        return new IOException("模型接口返回 HTTP " + status);
    }

    /** 只接受数字秒形式；HTTP-date 与缺失都交给本地退避策略。 */
    private static Long retryAfterSeconds(HttpResponse<String> response) {
        String value = response.headers().firstValue("Retry-After").orElse(null);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 服务商对超上下文的措辞不一致，按常见错误码与文案匹配。 */
    private static boolean looksLikeContextOverflow(String body) {
        if (body == null) {
            return false;
        }
        String text = body.toLowerCase(Locale.ROOT);
        return text.contains("context_length_exceeded") || text.contains("maximum context length")
                || text.contains("context length exceeded") || text.contains("prompt is too long")
                || text.contains("input is too long") || text.contains("input tokens exceed")
                || text.contains("context window") || text.contains("too many tokens")
                || text.contains("request_too_large");
    }
}
