package dev.huagent.prompt;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DoublePressTest {
    private final AtomicLong now = new AtomicLong();
    private final DoublePress press = new DoublePress(now::get);

    @Test
    void secondPressWithinTheWindowCounts() {
        assertFalse(press.press());
        advance(DoublePress.WINDOW_MILLIS);
        assertTrue(press.press());
        // 连按之后重新开始计数。
        assertFalse(press.press());
    }

    @Test
    void pressAfterTheWindowStartsOver() {
        assertFalse(press.press());
        advance(DoublePress.WINDOW_MILLIS + 1);
        assertFalse(press.press());
        advance(100);
        assertTrue(press.press());
    }

    @Test
    void resetForgetsTheFirstPress() {
        assertFalse(press.press());
        press.reset();
        assertFalse(press.press());
    }

    private void advance(long millis) {
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}
