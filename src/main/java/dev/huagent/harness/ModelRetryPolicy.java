package dev.huagent.harness;

/**
 * S15：模型请求的限流退避策略。首次重试等待 baseDelay，之后指数翻倍，上限 30 秒；
 * 服务商给出 Retry-After 时优先采用（上限 60 秒）。maxRetries 是额外重试次数，0 表示不重试。
 */
public final class ModelRetryPolicy {
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final long DEFAULT_BASE_DELAY_MILLIS = 2000;
    private static final long MAX_BACKOFF_MILLIS = 30_000;
    private static final long MAX_RETRY_AFTER_MILLIS = 60_000;

    /** 等待的实现，测试用它记录而不真正睡眠。 */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final int maxRetries;
    private final long baseDelayMillis;
    private final Sleeper sleeper;

    public ModelRetryPolicy(int maxRetries) {
        this(maxRetries, DEFAULT_BASE_DELAY_MILLIS, Thread::sleep);
    }

    public ModelRetryPolicy(int maxRetries, long baseDelayMillis, Sleeper sleeper) {
        if (maxRetries < 0 || baseDelayMillis < 0) {
            throw new IllegalArgumentException("重试次数与基础等待不能为负数");
        }
        this.maxRetries = maxRetries;
        this.baseDelayMillis = baseDelayMillis;
        this.sleeper = sleeper == null ? Thread::sleep : sleeper;
    }

    public int maxRetries() {
        return maxRetries;
    }

    /** attempt 从 1 开始；retryAfterSeconds 非空且为正时优先。 */
    public long delayMillis(int attempt, Long retryAfterSeconds) {
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            return Math.min(retryAfterSeconds * 1000L, MAX_RETRY_AFTER_MILLIS);
        }
        long delay = attempt <= 1 ? baseDelayMillis : baseDelayMillis * (1L << Math.min(attempt - 1, 20));
        return Math.min(delay, MAX_BACKOFF_MILLIS);
    }

    public void sleep(long millis) throws InterruptedException {
        if (millis > 0) {
            sleeper.sleep(millis);
        }
    }
}
