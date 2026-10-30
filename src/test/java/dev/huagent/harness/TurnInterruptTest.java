package dev.huagent.harness;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 用户中断：只在任务期间生效，中断任务线程；等确认回答时推迟到回答读完。 */
class TurnInterruptTest {
    @AfterEach
    void clearFlag() {
        Thread.interrupted();
    }

    @Test
    void requestOutsideATaskDoesNothing() {
        TurnInterrupt interrupt = new TurnInterrupt();
        interrupt.request();
        assertFalse(interrupt.requested());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void requestInterruptsTheTaskThreadAndEndClearsIt() throws Exception {
        TurnInterrupt interrupt = new TurnInterrupt();
        interrupt.begin();
        interrupt.request();
        assertTrue(interrupt.requested());
        assertThrows(InterruptedException.class, () -> Thread.sleep(5_000));

        // 中断请求留下的中断状态在本轮结束时清除，下一轮从头开始。
        interrupt.request();
        interrupt.end();
        assertFalse(interrupt.requested());
        assertFalse(Thread.currentThread().isInterrupted());
        interrupt.request();
        assertFalse(Thread.currentThread().isInterrupted(), "本轮结束后的请求不再生效");
    }

    @Test
    void endWithoutARequestLeavesOtherInterruptsAlone() {
        TurnInterrupt interrupt = new TurnInterrupt();
        interrupt.begin();
        Thread.currentThread().interrupt();
        interrupt.end();
        assertTrue(Thread.currentThread().isInterrupted(), "不是用户中断留下的中断状态照常传递");
    }

    @Test
    void listenersHearTaskStartAndEndOnce() {
        TurnInterrupt interrupt = new TurnInterrupt();
        List<Boolean> events = new ArrayList<>();
        interrupt.addListener(events::add);
        interrupt.end();
        interrupt.begin();
        interrupt.end();
        interrupt.end();
        assertEquals(List.of(true, false), events);
    }

    @Test
    void requestWhileTheTaskReadsAnAnswerWaitsUntilTheAnswerIsRead() throws Exception {
        TurnInterrupt interrupt = new TurnInterrupt();
        CountDownLatch asking = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        BufferedReader terminal = new BufferedReader(Reader.nullReader()) {
            @Override
            public String readLine() {
                asking.countDown();
                try {
                    // 行编辑器先请求中断，再以空回答结束确认；中断不能打断这里的等待。
                    assertTrue(answered.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    return "被中断";
                }
                return "";
            }
        };
        BufferedReader answers = interrupt.answers(terminal);
        Thread keys = new Thread(() -> {
            try {
                asking.await();
                interrupt.request();
                answered.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        interrupt.begin();
        keys.start();
        assertEquals("", answers.readLine());
        assertTrue(Thread.currentThread().isInterrupted(), "回答读完后补发中断");
        interrupt.end();
        assertFalse(Thread.currentThread().isInterrupted());
        // 中断状态清除后再等按键线程：带着中断状态 join 一个还没退出的线程会直接抛出 InterruptedException。
        keys.join();
    }

    @Test
    void answersOnOtherThreadsArePassedThrough() throws Exception {
        TurnInterrupt interrupt = new TurnInterrupt();
        interrupt.begin();
        interrupt.request();
        Thread.interrupted();
        String[] line = new String[1];
        boolean[] interrupted = new boolean[1];
        Thread reader = new Thread(() -> {
            try {
                line[0] = interrupt.answers(new BufferedReader(Reader.nullReader()) {
                    @Override
                    public String readLine() {
                        return "y";
                    }
                }).readLine();
            } catch (Exception e) {
                line[0] = e.toString();
            }
            interrupted[0] = Thread.currentThread().isInterrupted();
        });
        reader.start();
        reader.join();
        assertEquals("y", line[0]);
        assertFalse(interrupted[0]);
        interrupt.end();
    }
}
