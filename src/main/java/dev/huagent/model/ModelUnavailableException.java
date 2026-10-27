package dev.huagent.model;

import java.io.IOException;

/** 模型服务暂时不可用（HTTP 429/503/529），可按 Retry-After 或指数退避后重试。 */
public class ModelUnavailableException extends IOException {
    private final Long retryAfterSeconds;

    public ModelUnavailableException(String message, Long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds != null && retryAfterSeconds > 0 ? retryAfterSeconds : null;
    }

    /** 服务商建议的等待秒数；未提供时为 null，由调用方按退避策略决定。 */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
