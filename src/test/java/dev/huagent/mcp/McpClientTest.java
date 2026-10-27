package dev.huagent.mcp;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** 真实子进程（独立 JVM 运行 FakeMcpServer）上的 stdio JSON-RPC 往返。 */
@Timeout(60)
class McpClientTest {
    @TempDir
    Path root;
    private final List<String> log = new CopyOnWriteArrayList<>();
    private McpClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void handshakeListAndCallFollowProtocolOrder() throws Exception {
        Path record = root.resolve("record.txt");
        client = start("normal", record, 20);
        List<McpClient.ToolInfo> tools = client.listTools();
        assertEquals(List.of("echo", "add", "fail", "slow", "exit", "image"),
                tools.stream().map(McpClient.ToolInfo::name).toList());
        assertEquals("原样返回 text", tools.get(0).description());
        assertEquals("object", tools.get(0).inputSchema().getString("type"));
        JSONObject result = client.callTool("echo", JSONObject.of("text", "你好\n第二行 \"引号\""));
        assertEquals("你好\n第二行 \"引号\"", McpTool.render(result));
        assertEquals("3.5", McpTool.render(client.callTool("add", JSONObject.of("a", 1, "b", 2.5))));
        // initialize 之后先发 initialized 通知，再发其他请求。
        assertEquals(List.of("started -", "initialize", "notifications/initialized", "tools/list", "tools/call",
                "tools/call"), Files.readAllLines(record));
    }

    @Test
    void pagedToolListFollowsNextCursor() throws Exception {
        client = start("paged", null, 20);
        assertEquals(6, client.listTools().size());
    }

    @Test
    void toolErrorsAndProtocolErrorsAreDistinct() throws Exception {
        client = start("normal", null, 20);
        assertEquals("工具错误：磁盘已满", McpTool.render(client.callTool("fail", new JSONObject())));
        IOException error = assertThrows(IOException.class, () -> client.callTool("nope", new JSONObject()));
        assertTrue(error.getMessage().contains("Unknown tool: nope"), error.getMessage());
        assertTrue(error.getMessage().contains("-32602"), error.getMessage());
        assertTrue(client.connected(), "协议错误不断开连接");
    }

    @Test
    void timeoutSendsCancellationAndLaterCallsStillWork() throws Exception {
        Path record = root.resolve("record.txt");
        client = start("normal", record, 2);
        IOException error = assertThrows(IOException.class, () -> client.callTool("slow", new JSONObject()));
        assertTrue(error.getMessage().contains("超过 2 秒未响应"), error.getMessage());
        assertEquals("仍可用", McpTool.render(client.callTool("echo", JSONObject.of("text", "仍可用"))));
        assertTrue(Files.readAllLines(record).contains("notifications/cancelled"));
    }

    @Test
    void serverExitFailsPendingAndLaterCalls() throws Exception {
        client = start("normal", null, 20);
        IOException error = assertThrows(IOException.class, () -> client.callTool("exit", new JSONObject()));
        assertTrue(error.getMessage().contains("server 已断开"), error.getMessage());
        assertFalse(client.connected());
        IOException later = assertThrows(IOException.class, () -> client.callTool("echo", JSONObject.of("text", "x")));
        assertTrue(later.getMessage().contains("server 已断开"), later.getMessage());
    }

    @Test
    void crashBeforeHandshakeReportsAndKeepsStderrTail() {
        client = new McpClient(FakeServers.server("bad", "crash", null, 20), log::add);
        IOException error = assertThrows(IOException.class, () -> client.start(root, Map.of()));
        assertTrue(error.getMessage().contains("server 已断开"), error.getMessage());
        assertTrue(client.stderrTail().contains("missing API token"), client.stderrTail());
    }

    @Test
    void missingExecutableFailsToStart() {
        client = new McpClient(new McpConfig.Server("bad", "definitely-not-a-command-s18", List.of(), Map.of(),
                Duration.ofSeconds(5), List.of(), List.of()), log::add);
        IOException error = assertThrows(IOException.class, () -> client.start(root, Map.of()));
        assertTrue(error.getMessage().startsWith("无法启动 definitely-not-a-command-s18"), error.getMessage());
    }

    @Test
    void nonJsonOutputIsIgnoredAndServerRequestsAreAnswered() throws Exception {
        Path record = root.resolve("record.txt");
        client = start("noisy", record, 20);
        assertEquals(6, client.listTools().size());
        assertTrue(log.stream().anyMatch(line -> line.contains("非 JSON-RPC 内容")), log.toString());
        // 客户端回复了 ping 和未声明的 sampling 请求；server 收到的是没有 method 的响应，记为 null。
        assertEquals(2, Files.readAllLines(record).stream().filter("null"::equals).count());
    }

    @Test
    void environmentIsExpandedForTheChildProcess() throws Exception {
        Path record = root.resolve("record.txt");
        McpConfig.Server base = FakeServers.server("env", "normal", record, 20);
        McpConfig.Server server = new McpConfig.Server(base.name(), base.command(), base.args(),
                Map.of("FAKE_TOKEN", "tok-${SECRET}"), base.timeout(), List.of(), List.of());
        client = new McpClient(server, log::add);
        client.start(root, Map.of("SECRET", "42"));
        assertEquals("started tok-42", Files.readAllLines(record).get(0));
    }

    @Test
    void closeStopsTheProcess() throws Exception {
        client = start("normal", null, 20);
        client.close();
        assertFalse(client.connected());
        IOException error = assertThrows(IOException.class, () -> client.listTools());
        assertTrue(error.getMessage().contains("客户端已关闭"), error.getMessage());
    }

    private McpClient start(String mode, Path record, int timeout) throws Exception {
        McpClient started = new McpClient(FakeServers.server("fake", mode, record, timeout), log::add);
        started.start(root, Map.of());
        return started;
    }
}
