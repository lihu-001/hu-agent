package dev.huagent.model;

import java.io.IOException;

/** 模型接口暂时不可用：可重试的错误码或连接失败（分类见 ModelError），可按 Retry-After 或指数退避后重试。 */
public class ModelUnavailableException extends IOException {
    private final Long retryAfterSeconds;

    public ModelUnavailableException(String message, Long retryAfterSeconds) {
        this(message, retryAfterSeconds, null);
    }

    public ModelUnavailableException(String message, Long retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.retryAfterSeconds = retryAfterSeconds != null && retryAfterSeconds > 0 ? retryAfterSeconds : null;
    }

    /** 服务商建议的等待秒数；未提供时为 null，由调用方按退避策略决定。 */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
