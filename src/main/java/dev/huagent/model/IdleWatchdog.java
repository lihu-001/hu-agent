package dev.huagent.model;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 响应正文的空闲超时，事件流与普通响应（含错误响应）共用：HttpRequest 的超时只管到响应头为止，
 * 之后两次收到数据之间超过 timeout 就关闭连接，让阻塞中的读取以 IOException 结束，调用方用 expired() 认出是超时。
 * 通过 body() 读取，每读到数据就重新计时，不必等到一整行：很长的一行分段慢慢送达时不算空闲。读完调用 close 取消计时。
 */
final class IdleWatchdog implements AutoCloseable {
    /** 空闲计时共用一个守护线程；读完即取消，不留下等待中的任务。 */
    private static final ScheduledThreadPoolExecutor TIMER = timer();

    private final InputStream raw;
    private final InputStream body;
    private final long timeoutNanos;
    private volatile long lastNanos = System.nanoTime();
    private volatile boolean expired;
    private volatile boolean stopped;
    private volatile ScheduledFuture<?> next;

    IdleWatchdog(InputStream raw, Duration timeout) {
        this.raw = raw;
        this.body = new Touching();
        this.timeoutNanos = timeout.toNanos();
        schedule(timeoutNanos);
    }

    /** 读取用的正文：每读到数据都重新计时。 */
    InputStream body() {
        return body;
    }

    boolean expired() {
        return expired;
    }

    @Override
    public void close() {
        stopped = true;
        next.cancel(false);
    }

    private void touch() {
        lastNanos = System.nanoTime();
    }

    private void check() {
        if (stopped) {
            return;
        }
        long idle = System.nanoTime() - lastNanos;
        if (idle < timeoutNanos) {
            schedule(timeoutNanos - idle);
            return;
        }
        expired = true;
        try {
            raw.close();
        } catch (IOException e) {
            // 关闭失败时连接多半已经断开，读取同样会结束。
        }
    }

    private void schedule(long delayNanos) {
        next = TIMER.schedule(this::check, delayNanos, TimeUnit.NANOSECONDS);
    }

    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "model-stream-timeout");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    /** 直接继承 InputStream：readAllBytes 等默认实现都经过下面的 read，不会绕过计时。 */
    private final class Touching extends InputStream {
        @Override
        public int read() throws IOException {
            int c = raw.read();
            if (c >= 0) {
                touch();
            }
            return c;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = raw.read(buffer, offset, length);
            if (n > 0) {
                touch();
            }
            return n;
        }

        @Override
        public int available() throws IOException {
            return raw.available();
        }

        @Override
        public void close() throws IOException {
            raw.close();
        }
    }
}
