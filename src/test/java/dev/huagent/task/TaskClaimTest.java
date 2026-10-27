package dev.huagent.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** S13：多个队友并发认领时的锁与归还。 */
class TaskClaimTest {
    @TempDir
    Path root;

    @Test
    void concurrentClaimsOfSameTaskHaveExactlyOneWinner() throws Exception {
        TaskStore store = new TaskStore(root.resolve(TaskStore.DIRECTORY_NAME));
        TaskRecord task = store.create("抢", null);
        int contenders = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String owner = "m" + i;
            results.add(pool.submit(() -> {
                start.await();
                try {
                    return store.claim(task.id(), owner, true).owner();
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }));
        }
        start.countDown();
        List<String> winners = new ArrayList<>();
        for (Future<String> result : results) {
            String owner = result.get(10, TimeUnit.SECONDS);
            if (owner != null) {
                winners.add(owner);
            }
        }
        pool.shutdownNow();
        assertEquals(1, winners.size(), winners.toString());
        assertEquals(winners.get(0), store.load(task.id()).owner());
    }

    @Test
    void concurrentClaimNextGivesEachTaskToOneMember() throws Exception {
        TaskStore store = new TaskStore(root.resolve(TaskStore.DIRECTORY_NAME));
        for (int i = 0; i < 3; i++) {
            store.create("任务" + i, null);
        }
        int members = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(members);
        List<String> claimed = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < members; i++) {
            String owner = "m" + i;
            futures.add(pool.submit(() -> {
                start.await();
                TaskRecord task = store.claimNext(owner);
                if (task != null) {
                    claimed.add(task.id());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(3, claimed.size());
        assertEquals(3, claimed.stream().distinct().count(), "同一任务不会被认领两次");
    }

    @Test
    void singleClaimRejectsSecondTaskAndClaimNextSkipsBusyOrBlocked() throws Exception {
        TaskStore store = new TaskStore(root.resolve(TaskStore.DIRECTORY_NAME));
        TaskRecord first = store.create("先", null);
        TaskRecord second = store.create("后", null);
        TaskRecord blocked = store.create("阻塞", null);
        store.addDependencies(blocked.id(), List.of(first.id()));
        store.claim(first.id(), "alice", true);
        assertThrows(IllegalArgumentException.class, () -> store.claim(second.id(), "alice", true));
        assertNull(store.claimNext("alice"), "已有进行中的任务时不自动认领");
        // Lead（S10 的 claim）不受一次一个的限制。
        assertEquals("agent", store.claim(second.id(), "agent").owner());
        assertNull(store.claimNext("bob"), "剩下的任务被阻塞");
        store.complete(first.id(), "alice");
        assertEquals(blocked.id(), store.claimNext("bob").id());
    }

    @Test
    void releaseReturnsOnlyThatOwnersWorkAndStartupReleaseKeepsLead() throws Exception {
        TaskStore store = new TaskStore(root.resolve(TaskStore.DIRECTORY_NAME));
        TaskRecord a = store.create("a", null);
        TaskRecord b = store.create("b", null);
        TaskRecord c = store.create("c", null);
        store.claim(a.id(), "alice", true);
        store.claim(b.id(), "bob", true);
        store.claim(c.id(), "agent");
        assertEquals(List.of(a.id()), store.release("alice").stream().map(TaskRecord::id).toList());
        assertEquals(TaskStatus.PENDING, store.load(a.id()).status());
        assertNull(store.load(a.id()).owner());
        assertEquals(List.of(b.id()), store.releaseAllExcept("agent").stream().map(TaskRecord::id).toList());
        assertEquals("agent", store.load(c.id()).owner());
        assertTrue(new TaskStore(root.resolve("missing")).releaseAllExcept("agent").isEmpty());
        assertNull(new TaskStore(root.resolve("missing")).claimNext("alice"));
    }

    @Test
    void teammateToolsCannotCreateOrRewireAndClaimOneAtATime() throws Exception {
        TaskStore store = new TaskStore(root.resolve(TaskStore.DIRECTORY_NAME));
        TaskRecord a = store.create("a", null);
        TaskRecord b = store.create("b", null);
        var tools = TaskTools.createForTeammate(store, "alice");
        assertEquals(TaskTools.TEAMMATE_NAMES, tools.stream().map(tool -> tool.definition().name()).toList());
        var claim = tools.stream().filter(tool -> tool.definition().name().equals(TaskTools.CLAIM)).findFirst()
                .orElseThrow();
        assertTrue(claim.execute("{\"id\":\"" + a.id() + "\"}").contains("认领者 alice"));
        assertThrows(IllegalArgumentException.class, () -> claim.execute("{\"id\":\"" + b.id() + "\"}"));
    }
}
