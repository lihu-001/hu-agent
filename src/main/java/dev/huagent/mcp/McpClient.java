package dev.huagent.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import dev.huagent.config.HuVersion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个 stdio MCP server 的客户端：子进程的 stdin/stdout 上逐行收发 JSON-RPC 2.0 消息。
 * 只实现本课用到的部分：initialize 握手、tools/list（分页）、tools/call 和超时取消；不支持 resources、prompts 与 sampling。
 */
public final class McpClient implements AutoCloseable {
    public static final String PROTOCOL_VERSION = "2025-06-18";
    /** 单条消息上限：超出说明 server 失控，直接断开，避免无限占用内存。 */
    static final int MAX_LINE_CHARS = 1 << 20;
    static final int MAX_TOOLS = 64;
    private static final int STDERR_TAIL_CHARS = 2000;
    private static final int MAX_STARTUP_STDERR_LINES = 20;
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    public record ToolInfo(String name, String description, JSONObject inputSchema) {
    }

    private final McpConfig.Server server;
    private final Consumer<String> log;
    private final Map<Long, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final StringBuilder stderrTail = new StringBuilder();
    /** 写入锁与 start 无关：握手等待期间，读取线程仍需回复 server 发来的 ping。 */
    private final Object writeLock = new Object();
    private Process process;
    private OutputStream stdin;
    private Thread stderrReader;
    private volatile String closedReason;
    private volatile boolean starting = true;

    public McpClient(McpConfig.Server server, Consumer<String> log) {
        this.server = server;
        this.log = log;
    }

    public String name() {
        return server.name();
    }

    /** 启动进程并完成 initialize 握手；失败时进程已停止。 */
    public void start(Path directory, Map<String, String> environment) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(server.command());
        command.addAll(server.args());
        // 参数列表直接交给操作系统，不经过 Shell，配置中的空格和引号不会被二次解释。
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        for (Map.Entry<String, String> entry : server.env().entrySet()) {
            builder.environment().put(entry.getKey(), expand(entry.getValue(), environment));
        }
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("无法启动 " + server.command() + "（" + e.getMessage() + "）", e);
        }
        log.accept("MCP> " + server.name() + " 进程已启动（pid " + process.pid() + "），正在握手，最多等待 "
                + server.timeout().toSeconds() + " 秒…");
        stdin = process.getOutputStream();
        daemon("mcp-" + server.name() + "-stdout", this::readMessages);
        stderrReader = daemon("mcp-" + server.name() + "-stderr", this::readStderr);
        try {
            JSONObject params = new JSONObject();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.put("capabilities", new JSONObject());
            params.put("clientInfo", JSONObject.of("name", "hu-agent", "version", HuVersion.current()));
            long started = System.nanoTime();
            JSONObject result = request("initialize", params, server.timeout());
            if (!(result.get("protocolVersion") instanceof String version)) {
                throw new IOException("initialize 响应缺少 protocolVersion");
            }
            String info = result.get("serverInfo") instanceof JSONObject serverInfo
                    ? serverInfo.get("name") + (serverInfo.get("version") instanceof String v ? " " + v : "") : "未提供 serverInfo";
            log.accept("MCP> " + server.name() + " 握手完成（" + info + "，协议 " + version + "，耗时 "
                    + (System.nanoTime() - started) / 1_000_000 + " ms）");
            if (!version.equals(PROTOCOL_VERSION)) {
                log.accept("MCP> " + server.name() + " 使用协议版本 " + version + "，本客户端按 " + PROTOCOL_VERSION + " 处理。");
            }
            notify("notifications/initialized");
        } catch (IOException | InterruptedException | RuntimeException e) {
            close();
            throw e;
        }
    }

    /** 列出全部工具，按 nextCursor 翻页；超过上限的部分丢弃。 */
    public List<ToolInfo> listTools() throws IOException, InterruptedException {
        List<ToolInfo> tools = new ArrayList<>();
        String cursor = null;
        do {
            JSONObject params = new JSONObject();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JSONObject result = request("tools/list", params, server.timeout());
            if (!(result.get("tools") instanceof JSONArray page)) {
                throw new IOException("tools/list 响应缺少 tools 数组");
            }
            for (Object item : page) {
                if (tools.size() >= MAX_TOOLS) {
                    log.accept("MCP> " + server.name() + " 的工具超过 " + MAX_TOOLS + " 个，其余忽略。");
                    return tools;
                }
                if (item instanceof JSONObject tool && tool.get("name") instanceof String name
                        && tool.get("inputSchema") instanceof JSONObject schema) {
                    tools.add(new ToolInfo(name, tool.get("description") instanceof String text ? text : "", schema));
                } else {
                    log.accept("MCP> " + server.name() + " 返回了缺少 name 或 inputSchema 的工具，已跳过。");
                }
            }
            cursor = result.get("nextCursor") instanceof String next && !next.isEmpty() ? next : null;
        } while (cursor != null);
        return tools;
    }

    public JSONObject callTool(String name, JSONObject arguments) throws IOException, InterruptedException {
        return request("tools/call", JSONObject.of("name", name, "arguments", arguments), server.timeout());
    }

    JSONObject request(String method, JSONObject params, Duration timeout) throws IOException, InterruptedException {
        long id = ids.incrementAndGet();
        CompletableFuture<JSONObject> response = new CompletableFuture<>();
        pending.put(id, response);
        try {
            JSONObject message = JSONObject.of("jsonrpc", "2.0", "id", id, "method", method);
            message.put("params", params);
            send(message);
            JSONObject reply;
            try {
                reply = response.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // 规范要求超时后发送取消通知；server 可以忽略，迟到的响应因 ID 已移除而丢弃。
                trySend(JSONObject.of("jsonrpc", "2.0", "method", "notifications/cancelled",
                        "params", JSONObject.of("requestId", id, "reason", "timeout")));
                throw new IOException(method + " 超过 " + timeout.toSeconds() + " 秒未响应");
            } catch (InterruptedException e) {
                trySend(JSONObject.of("jsonrpc", "2.0", "method", "notifications/cancelled",
                        "params", JSONObject.of("requestId", id, "reason", "interrupted")));
                throw e;
            } catch (ExecutionException e) {
                throw new IOException(e.getCause().getMessage(), e.getCause());
            }
            if (reply.get("error") instanceof JSONObject error) {
                throw new IOException(method + " 失败：" + error.get("message") + "（code " + error.get("code") + "）");
            }
            if (!(reply.get("result") instanceof JSONObject result)) {
                throw new IOException(method + " 响应缺少 result 对象");
            }
            return result;
        } finally {
            pending.remove(id);
        }
    }

    private void notify(String method) throws IOException {
        send(JSONObject.of("jsonrpc", "2.0", "method", method));
    }

    private void send(JSONObject message) throws IOException {
        synchronized (writeLock) {
            if (closedReason != null) {
                throw new IOException("server 已断开：" + closedReason);
            }
            try {
                // 规范要求消息内不能有换行；fastjson2 默认输出单行并转义字符串中的换行。
                stdin.write((message.toJSONString() + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException e) {
                // 进程已退出时写入报管道错误，统一成“已断开”。
                disconnect("写入失败，进程可能已退出");
                throw new IOException("server 已断开：" + closedReason, e);
            }
        }
    }

    private void trySend(JSONObject message) {
        try {
            send(message);
        } catch (IOException ignored) {
            // 连接已断开时取消通知没有意义。
        }
    }

    private void readMessages() {
        String reason = "输出已结束";
        try (Reader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                if (ch != '\n') {
                    if (line.length() >= MAX_LINE_CHARS) {
                        reason = "单条消息超过 " + MAX_LINE_CHARS + " 字符";
                        break;
                    }
                    line.append((char) ch);
                    continue;
                }
                String text = line.toString().strip();
                line.setLength(0);
                if (!text.isEmpty()) {
                    handle(text);
                }
            }
        } catch (IOException e) {
            reason = "读取失败（" + e.getClass().getSimpleName() + "）";
        }
        disconnect(reason);
    }

    private void handle(String text) {
        JSONObject message;
        try {
            message = JSON.parseObject(text);
        } catch (RuntimeException e) {
            log.accept("MCP> " + server.name() + " 输出了非 JSON-RPC 内容，已忽略（stdout 只能写协议消息，日志应写 stderr）。");
            return;
        }
        if (message == null) {
            return;
        }
        Object id = message.get("id");
        if (message.containsKey("method")) {
            // server 发来的请求：只回应 ping，其余（sampling、roots 等）本客户端未声明，按规范回复方法不存在。
            if (id != null) {
                JSONObject reply = JSONObject.of("jsonrpc", "2.0", "id", id);
                if ("ping".equals(message.get("method"))) {
                    reply.put("result", new JSONObject());
                } else {
                    reply.put("error", JSONObject.of("code", -32601, "message", "Method not found"));
                }
                trySend(reply);
            }
            return;
        }
        if (id instanceof Number number) {
            CompletableFuture<JSONObject> response = pending.get(number.longValue());
            if (response != null) {
                response.complete(message);
            }
        }
    }

    private void readStderr() {
        // 启动期间逐行转发（npx 下载进度、启动报错），之后只保留末尾一段用于诊断，避免运行日志刷满终端。
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            int echoed = 0;
            while ((line = reader.readLine()) != null) {
                synchronized (stderrTail) {
                    stderrTail.append(line).append('\n');
                    if (stderrTail.length() > STDERR_TAIL_CHARS) {
                        stderrTail.delete(0, stderrTail.length() - STDERR_TAIL_CHARS);
                    }
                }
                if (starting && !line.isBlank() && echoed < MAX_STARTUP_STDERR_LINES) {
                    echoed++;
                    log.accept("MCP> " + server.name() + " stderr: " + truncate(line.strip(), 300)
                            + (echoed == MAX_STARTUP_STDERR_LINES ? "（之后的 stderr 不再显示）" : ""));
                }
            }
        } catch (IOException ignored) {
            // 进程结束后错误流关闭。
        }
    }

    /** 启动阶段结束（握手和列工具完成或失败）后调用，此后 stderr 不再转发到终端。 */
    public void finishStartup() {
        starting = false;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** server 最近的 stderr，用于启动失败或断开时的诊断。 */
    public String stderrTail() {
        synchronized (stderrTail) {
            return stderrTail.toString().strip();
        }
    }

    public boolean connected() {
        return closedReason == null;
    }

    private void disconnect(String reason) {
        if (closedReason == null) {
            closedReason = reason;
        }
        IOException failure = new IOException("server 已断开：" + closedReason);
        pending.values().forEach(response -> response.completeExceptionally(failure));
    }

    @Override
    public void close() {
        disconnect("客户端已关闭");
        if (process == null) {
            return;
        }
        // 规范的 stdio 关闭顺序：先关 stdin 让 server 自行退出，等不到再强制终止进程树。
        try {
            stdin.close();
        } catch (IOException ignored) {
            // 已断开。
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                List<ProcessHandle> descendants = process.descendants().toList();
                for (int i = descendants.size() - 1; i >= 0; i--) {
                    descendants.get(i).destroyForcibly();
                }
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            // 进程结束后等错误流读完，启动失败时诊断信息才完整。
            stderrReader.join(1000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** env 值可写 ${NAME} 引用当前进程的环境变量，令牌不必写进配置文件；变量不存在时不启动。 */
    static String expand(String value, Map<String, String> environment) throws IOException {
        Matcher matcher = VARIABLE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = environment.get(matcher.group(1));
            if (replacement == null) {
                throw new IOException("环境变量 " + matcher.group(1) + " 未设置");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Thread daemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
