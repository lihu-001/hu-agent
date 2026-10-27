package dev.huagent.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MessageBusTest {
    @TempDir
    Path root;

    @Test
    void sendAppendsJsonLinesAndDrainReturnsInOrderThenDeletes() throws Exception {
        List<String> delivered = new ArrayList<>();
        MessageBus bus = new MessageBus(root.resolve(MessageBus.DIRECTORY_NAME), delivered::add);
        bus.send(new TeamMessage("alice", "lead", TeamMessage.MESSAGE, "第一条"));
        bus.send(new TeamMessage("bob", "lead", TeamMessage.RESULT, "第二条"));
        Path file = bus.directory().resolve("lead.jsonl");
        assertEquals(2, Files.readAllLines(file, StandardCharsets.UTF_8).size());
        assertEquals(List.of("lead", "lead"), delivered);

        List<TeamMessage> mail = bus.drain("lead");
        assertEquals(List.of("第一条", "第二条"), mail.stream().map(TeamMessage::content).toList());
        assertEquals("alice", mail.get(0).from());
        assertEquals(TeamMessage.RESULT, mail.get(1).type());
        assertFalse(Files.exists(file), "读取后删除");
        assertTrue(bus.drain("lead").isEmpty());
        assertTrue(bus.drain("nobody").isEmpty());
    }

    @Test
    void requestIdRoundTripsAndBrokenLinesAreSkipped() throws Exception {
        MessageBus bus = new MessageBus(root, to -> { });
        bus.send(new TeamMessage("lead", "alice", TeamMessage.SHUTDOWN_REQUEST, "停", "req_abc123", null));
        Files.writeString(root.resolve("alice.jsonl"), "不是 JSON\n{\"from\":1}\n\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        List<TeamMessage> mail = bus.drain("alice");
        assertEquals(1, mail.size());
        assertEquals("req_abc123", mail.get(0).requestId());
    }

    @Test
    void namesAreLowercaseAndContentIsBounded() {
        MessageBus bus = new MessageBus(root, to -> { });
        for (String name : List.of("Alice", "1bob", "a b", "../x", "", "a".repeat(33))) {
            assertThrows(IllegalArgumentException.class, () -> MessageBus.validateName(name), name);
        }
        MessageBus.validateName("back-end_2");
        assertThrows(IllegalArgumentException.class, () -> bus.send(new TeamMessage("lead", "alice",
                TeamMessage.MESSAGE, "x".repeat(MessageBus.MAX_CONTENT_CHARS + 1))));
    }

    @Test
    void awaitWakesOnMailForTheRecipient() throws Exception {
        MessageBus bus = new MessageBus(root, to -> { });
        CountDownLatch woke = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                bus.await("alice", Duration.ofSeconds(30));
                woke.countDown();
            } catch (InterruptedException ignored) {
            }
        });
        waiter.start();
        Thread.sleep(100);
        assertEquals(1, woke.getCount(), "没有消息时保持阻塞");
        bus.send(new TeamMessage("lead", "alice", TeamMessage.MESSAGE, "醒醒"));
        assertTrue(woke.await(5, TimeUnit.SECONDS), "发送后立即唤醒，不等超时");
        waiter.join(1000);
    }

    @Test
    void awaitReturnsImmediatelyWhenMailIsAlreadyThereAndWakeAllWakesIdleMembers() throws Exception {
        MessageBus bus = new MessageBus(root, to -> { });
        bus.send(new TeamMessage("lead", "alice", TeamMessage.MESSAGE, "早就到了"));
        long start = System.nanoTime();
        bus.await("alice", Duration.ofSeconds(30));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));

        CountDownLatch woke = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                bus.await("bob", Duration.ofSeconds(30));
                woke.countDown();
            } catch (InterruptedException ignored) {
            }
        });
        waiter.start();
        Thread.sleep(100);
        bus.wakeAll();
        assertTrue(woke.await(5, TimeUnit.SECONDS));
        waiter.join(1000);
    }

    @Test
    void clearRemovesStaleInboxes() throws Exception {
        MessageBus bus = new MessageBus(root.resolve("box"), to -> { });
        assertEquals(0, bus.clear());
        bus.send(new TeamMessage("a", "lead", TeamMessage.MESSAGE, "旧"));
        bus.send(new TeamMessage("a", "bob", TeamMessage.MESSAGE, "旧"));
        assertEquals(2, bus.clear());
        assertTrue(bus.drain("lead").isEmpty());
    }
}
