package dev.huagent.prompt;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 连按判断，与 Claude Code 相同：距上一次按下不超过 800 ms 再按算连按。
 * 第一次按下之后进入等待（pending），超时或连按后结束；Esc 与 Ctrl+C 各用一个。
 */
final class DoublePress {
    static final long WINDOW_MILLIS = 800;

    private final LongSupplier nanoTime;
    private long last;
    private boolean pending;

    DoublePress(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /** 按下一次：是连按的第二下时返回 true 并结束等待，否则开始等待下一下。 */
    synchronized boolean press() {
        long now = nanoTime.getAsLong();
        boolean twice = pending && now - last <= TimeUnit.MILLISECONDS.toNanos(WINDOW_MILLIS);
        pending = !twice;
        last = now;
        return twice;
    }

    /** 不再等待第二下（如输入行已空，Esc 没有可清空的内容）。 */
    synchronized void reset() {
        pending = false;
    }
}
