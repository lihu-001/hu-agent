package dev.huagent.model;

import cn.hutool.core.util.StrUtil;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Locale;

/**
 * 模型接口的错误码统一在这里管理：每个 HTTP 状态码的说明与能否重试，以及没有状态码的连接失败和请求超时。
 * 客户端按这里把失败分类成异常（可重试的为 ModelUnavailableException，上下文超长为 ContextTooLargeException），
 * ChatLoop 按 retryable 决定是否重试；别处不判断具体状态码。
 */
public enum ModelError {
    // 请求本身或配置有问题，重试结果不会变。
    BAD_REQUEST(400, "请求无效", false),
    UNAUTHORIZED(401, "api-key 无效或已过期", false),
    FORBIDDEN(403, "没有访问权限", false),
    NOT_FOUND(404, "接口地址或模型不存在", false),
    PAYLOAD_TOO_LARGE(413, "请求过大", false),
    // 服务端暂时的问题，稍后重试可能成功（与 Anthropic、OpenAI 官方 SDK 的重试范围相同）。
    REQUEST_TIMEOUT(408, "请求超时", true),
    CONFLICT(409, "请求冲突", true),
    RATE_LIMITED(429, "限流", true),
    SERVER_ERROR(500, "服务端错误", true),
    BAD_GATEWAY(502, "网关错误", true),
    SERVICE_UNAVAILABLE(503, "服务暂不可用", true),
    GATEWAY_TIMEOUT(504, "网关超时", true),
    OVERLOADED(529, "服务过载", true);

    private final int status;
    private final String label;
    private final boolean retryable;

    ModelError(int status, String label, boolean retryable) {
        this.status = status;
        this.label = label;
        this.retryable = retryable;
    }

    public int status() {
        return status;
    }

    public String label() {
        return label;
    }

    public boolean retryable() {
        return retryable;
    }

    /** 状态码对应的错误；表里没有的 5xx 按服务端错误处理，其余返回 null（不重试、不加说明）。 */
    public static ModelError of(int status) {
        for (ModelError error : values()) {
            if (error.status == status) {
                return error;
            }
        }
        return status >= 500 && status < 600 ? SERVER_ERROR : null;
    }

    /** 能否重试：可重试的错误码、连接失败与请求超时。 */
    public static boolean retryable(IOException e) {
        return e instanceof ModelUnavailableException || e instanceof HttpTimeoutException;
    }

    /** 终端上显示的失败原因；超时异常的消息是英文，换成中文。 */
    public static String describe(IOException e) {
        if (e instanceof HttpConnectTimeoutException) {
            return "连接模型接口超时";
        }
        if (e instanceof HttpTimeoutException) {
            return "模型接口请求超时";
        }
        return StrUtil.isBlank(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * 失败的 HTTP 响应分类成异常：可重试的为 ModelUnavailableException（带 Retry-After 建议），
     * 400/413 且正文像上下文超长的为 ContextTooLargeException，其余为通用 IOException。
     * 正文可能含敏感信息，不进入异常消息。
     */
    static IOException failure(HttpResponse<String> response) {
        int status = response.statusCode();
        ModelError error = of(status);
        String message = "模型接口返回 HTTP " + status;
        if ((error == BAD_REQUEST || error == PAYLOAD_TOO_LARGE) && looksLikeContextOverflow(response.body())) {
            return new ContextTooLargeException(message + "（上下文超长）");
        }
        if (error == null) {
            return new IOException(message);
        }
        message += "（" + error.label + "）";
        return error.retryable ? new ModelUnavailableException(message, retryAfterSeconds(response))
                : new IOException(message);
    }

    /** 发送请求时的网络失败（连不上、连接被重置、TLS 握手失败等）按可重试处理；超时保持 HttpTimeoutException。 */
    static IOException connectionFailure(IOException e) {
        if (e instanceof HttpTimeoutException) {
            return e;
        }
        // HttpClient 的连接异常常常没有消息，原因在 cause 里。
        String detail = e.getMessage();
        if (StrUtil.isBlank(detail) && e.getCause() != null) {
            detail = e.getCause().getMessage();
        }
        return new ModelUnavailableException("连接模型接口失败（" + e.getClass().getSimpleName()
                + (StrUtil.isBlank(detail) ? "" : "：" + detail) + "）", null, e);
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
