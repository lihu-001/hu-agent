package dev.huagent.harness;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelRetryPolicyTest {
    @Test
    void backoffDoublesAndCapsAtThirtySeconds() {
        ModelRetryPolicy policy = new ModelRetryPolicy(5, 2000, millis -> { });
        assertEquals(2000, policy.delayMillis(1, null));
        assertEquals(4000, policy.delayMillis(2, null));
        assertEquals(8000, policy.delayMillis(3, null));
        assertEquals(16000, policy.delayMillis(4, null));
        assertEquals(30000, policy.delayMillis(5, null));
        assertEquals(30000, policy.delayMillis(9, null));
    }

    @Test
    void retryAfterAdviceWinsAndCapsAtSixtySeconds() {
        ModelRetryPolicy policy = new ModelRetryPolicy(3, 2000, millis -> { });
        assertEquals(5000, policy.delayMillis(1, 5L));
        assertEquals(60000, policy.delayMillis(1, 600L));
        // 非正数的建议视为没有建议。
        assertEquals(2000, policy.delayMillis(1, 0L));
        assertEquals(2000, policy.delayMillis(1, -3L));
        assertEquals(2000, policy.delayMillis(1, null));
    }

    @Test
    void sleepDelegatesToSleeperAndSkipsNonPositive() throws InterruptedException {
        List<Long> slept = new ArrayList<>();
        ModelRetryPolicy policy = new ModelRetryPolicy(3, 100, slept::add);
        policy.sleep(250);
        policy.sleep(0);
        assertEquals(List.of(250L), slept);
    }

    @Test
    void rejectsNegativeConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ModelRetryPolicy(-1, 1000, millis -> { }));
        assertThrows(IllegalArgumentException.class, () -> new ModelRetryPolicy(1, -1, millis -> { }));
        ModelRetryPolicy policy = new ModelRetryPolicy(0);
        assertEquals(0, policy.maxRetries());
    }
}
