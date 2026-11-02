package dev.huagent.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 测试用的模型接口：按顺序写出响应的各段，每段写完就 flush；可以在某一段之前停住，直到 close。 */
final class StreamServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CountDownLatch release = new CountDownLatch(1);
    final AtomicReference<JSONObject> request = new AtomicReference<>();
    volatile int status = 200;
    volatile String contentType = "text/event-stream";
    volatile String retryAfter;
    volatile List<String> chunks = List.of();
    /** 写完这么多段后停住；-1 表示不停。 */
    volatile int stallAfter = -1;

    StreamServer(String path) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            try (exchange) {
                request.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                exchange.getResponseHeaders().set("Content-Type", contentType);
                if (retryAfter != null) {
                    exchange.getResponseHeaders().set("Retry-After", retryAfter);
                }
                exchange.sendResponseHeaders(status, 0);
                OutputStream body = exchange.getResponseBody();
                for (int i = 0; i <= chunks.size(); i++) {
                    if (i == stallAfter) {
                        release.await(10, TimeUnit.SECONDS);
                        return;
                    }
                    if (i < chunks.size()) {
                        body.write(chunks.get(i).getBytes(StandardCharsets.UTF_8));
                        body.flush();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 一个只有 data 的事件。 */
    static String data(String data) {
        return "data: " + data + "\n\n";
    }

    /** 带事件类型的事件（Anthropic 的写法）。 */
    static String event(String type, String data) {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
