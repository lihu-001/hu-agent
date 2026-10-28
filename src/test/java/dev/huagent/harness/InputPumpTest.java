package dev.huagent.harness;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InputPumpTest {
    @Test
    void readsOnlyWhenRequestedAndOneLineAtATime() throws Exception {
        CountingReader terminal = new CountingReader("第一行", "第二行");
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        InputPump pump = new InputPump(terminal, line -> lines.add(line == null ? "<end>" : line), "test-input");

        assertNull(lines.poll(200, TimeUnit.MILLISECONDS), "没有请求时不读");
        assertEquals(0, terminal.reads.get());

        // 读到之前的重复请求合并为一次读取。
        pump.request();
        pump.request();
        assertEquals("第一行", lines.poll(2, TimeUnit.SECONDS));
        assertNull(lines.poll(200, TimeUnit.MILLISECONDS));
        assertEquals(1, terminal.reads.get());

        pump.request();
        assertEquals("第二行", lines.poll(2, TimeUnit.SECONDS));
        pump.request();
        assertEquals("<end>", lines.poll(2, TimeUnit.SECONDS));
        assertEquals(3, terminal.reads.get());
    }

    @Test
    void failingTerminalEndsInput() throws Exception {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        BufferedReader broken = new BufferedReader(Reader.nullReader()) {
            @Override
            public String readLine() {
                throw new IllegalStateException("终端已关闭");
            }
        };
        InputPump pump = new InputPump(broken, line -> lines.add(line == null ? "<end>" : line), "test-input");
        pump.request();
        assertEquals("<end>", lines.poll(2, TimeUnit.SECONDS));
    }

    private static final class CountingReader extends BufferedReader {
        private final Deque<String> lines;
        private final AtomicInteger reads = new AtomicInteger();

        CountingReader(String... lines) {
            super(Reader.nullReader());
            this.lines = new ArrayDeque<>(List.of(lines));
        }

        @Override
        public String readLine() {
            reads.incrementAndGet();
            return lines.poll();
        }
    }
}
