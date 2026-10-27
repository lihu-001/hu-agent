package dev.huagent.harness;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EventHubTest {
    @Test
    void publishesDrainsOnceAndRequeuesAtFrontInOrder() {
        EventHub hub = new EventHub();
        hub.publish("第一条");
        hub.publish("第二条");
        assertTrue(hub.hasPending());
        assertEquals(List.of("第一条", "第二条"), hub.drain());
        assertFalse(hub.hasPending());
        assertTrue(hub.drain().isEmpty());
        hub.requeue(List.of("第一条", "第二条"));
        assertEquals(List.of("第一条", "第二条"), hub.drain());
    }

    @Test
    void wakesListenersOnPublishAndToleratesListenerFailures() {
        EventHub hub = new EventHub();
        AtomicInteger woken = new AtomicInteger();
        hub.addListener(() -> {
            throw new IllegalStateException("监听者故障");
        });
        hub.addListener(woken::incrementAndGet);
        hub.wakeup();
        assertEquals(1, woken.get());
        hub.publish("x");
        assertEquals(2, woken.get());
    }

    @Test
    void syntheticTurnFlagIsVisibleUntilReset() {
        EventHub hub = new EventHub();
        assertFalse(hub.inSyntheticTurn());
        hub.setSyntheticTurn(true);
        assertTrue(hub.inSyntheticTurn());
        hub.setSyntheticTurn(false);
        assertFalse(hub.inSyntheticTurn());
    }

    @Test
    void rendersScheduledEventsInTaggedBlock() {
        String rendered = EventHub.render(List.of("[s1（备份）] completed\nok"));
        assertTrue(rendered.startsWith("<system-events>"));
        assertTrue(rendered.endsWith("</system-events>"));
        assertTrue(rendered.contains("[s1（备份）] completed"));
    }

    @Test
    void concurrentPublishAndDrainLosesNothing() throws InterruptedException {
        EventHub hub = new EventHub();
        int producers = 4;
        int perProducer = 200;
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < producers; i++) {
            Thread thread = new Thread(() -> {
                for (int j = 0; j < perProducer; j++) {
                    hub.publish("t");
                }
            });
            thread.start();
            threads.add(thread);
        }
        int drained = 0;
        for (Thread thread : threads) {
            thread.join();
        }
        while (hub.hasPending()) {
            drained += hub.drain().size();
        }
        assertEquals(producers * perProducer, drained);
    }
}
