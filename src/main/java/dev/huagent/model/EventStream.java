package dev.huagent.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 流式响应（Server-Sent Events）的读取，两种协议共用：按行解析出事件依次交给 listener，listener 返回 false 时不再读取。
 * 两次收到数据之间超过 idleTimeout 时关闭连接，按请求超时（HttpTimeoutException）处理，见 IdleWatchdog。
 * 读取中线程被中断（用户按 Esc）时抛出 InterruptedException；连接中途断开为可重试的失败（见 ModelError.streamBroken）。
 * listener 抛出的异常原样传出；连接由调用方关闭。
 */
final class EventStream {
    @FunctionalInterface
    interface Listener {
        /** type 是 event 字段，没有时为 "message"；data 是各 data 行以换行连接。返回 false 表示已收到结束标记。 */
        boolean event(String type, String data) throws IOException;
    }

    private EventStream() {
    }

    static void read(InputStream body, Duration idleTimeout, Listener listener)
            throws IOException, InterruptedException {
        try (IdleWatchdog watchdog = new IdleWatchdog(body, idleTimeout)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(watchdog.body(), StandardCharsets.UTF_8));
            String type = null;
            StringBuilder data = null;
            while (true) {
                String line = readLine(reader, watchdog, idleTimeout);
                if (line == null) {
                    // 最后一个事件后面没有空行时照样交出。
                    if (data != null) {
                        listener.event(type == null ? "message" : type, data.toString());
                    }
                    return;
                }
                if (line.isEmpty()) {
                    if (data != null && !listener.event(type == null ? "message" : type, data.toString())) {
                        return;
                    }
                    type = null;
                    data = null;
                    continue;
                }
                if (line.startsWith(":")) {
                    // 注释行，接口常用它保持连接。
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if ("event".equals(field)) {
                    type = value;
                } else if ("data".equals(field)) {
                    data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
                }
                // id、retry 等字段用不到。
            }
        }
    }

    /** 读一行；读取前后检查中断：已缓冲的数据不会阻塞，按键后也要尽快停下。 */
    private static String readLine(BufferedReader reader, IdleWatchdog watchdog, Duration idleTimeout)
            throws IOException, InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("模型请求被中断");
        }
        try {
            return reader.readLine();
        } catch (IOException e) {
            // 中断与空闲超时都是关闭连接让读取结束的，先认出这两种。
            if (Thread.interrupted()) {
                throw new InterruptedException("模型请求被中断");
            }
            if (watchdog.expired()) {
                throw new HttpTimeoutException("模型接口的流式响应超过 " + idleTimeout.toSeconds() + " 秒没有数据");
            }
            throw ModelError.streamBroken(e);
        }
    }
}
