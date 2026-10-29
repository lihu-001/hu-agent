package dev.huagent.mcp;

import dev.huagent.app.McpMain;
import dev.huagent.app.ScheduleMain;
import dev.huagent.app.TeamMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.skill.SkillCatalog;
import dev.huagent.todo.TodoList;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** 通过 McpMain 组装的完整会话：信任确认、工具发现、权限、Hook，以及只有主 Agent 拥有 MCP 工具。 */
@Timeout(120)
class McpLoopTest {
    @TempDir
    Path root;
    private Path work;
    private Path project;
    private Path record;
    private final List<List<String>> toolNames = new CopyOnWriteArrayList<>();
    private final List<List<ChatMessage>> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(root.resolve("work"));
        project = Files.createDirectories(root.resolve("project"));
        record = root.resolve("record.txt");
    }

    @Test
    void withoutConfigurationToolsAndPromptMatchS13() throws Exception {
        StringWriter buffer = new StringWriter();
        TeamMain.Session session = session("x\nexit\n", buffer, answers(new ChatMessage("assistant", "ok")), false);
        session.run();
        assertEquals(27, toolNames.get(0).size());
        assertEquals(ScheduleMain.systemPrompt(SkillCatalog.empty(), List.of()), requests.get(0).get(0).content());
        assertFalse(buffer.toString().contains("MCP"), buffer.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"n\n", "\n", "whatever\n", ""})
    void refusalOrEofStartsNoServerAndKeepsInput(String answer) throws Exception {
        config(Map.of());
        StringWriter buffer = new StringWriter();
        TeamMain.Session session = session(answer + "x\nexit\n", buffer, answers(new ChatMessage("assistant", "ok")), false);
        session.run();
        assertTrue(buffer.toString().contains("MCP 信任> 本次未启动 MCP server"), buffer.toString());
        assertFalse(Files.exists(record), "server 进程没有启动");
        assertFalse(Files.exists(root.resolve("mcp-trust")));
        if (!answer.isEmpty()) {
            assertEquals(27, toolNames.get(0).size());
        }
    }

    @Test
    void trustPromptShowsCommandAndEnvNamesButNotValues() throws Exception {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("env", Map.of("FAKE_TOKEN", "sk-secret-value"));
        options.put("allow", List.of("echo"));
        config(options);
        StringWriter buffer = new StringWriter();
        session("n\nexit\n", buffer, answers(), false).run();
        String text = buffer.toString();
        assertTrue(text.contains("MCP 信任确认> 首次启用或配置/脚本已变化。"), text);
        assertTrue(text.contains("  fake：" + FakeServers.java()), text);
        assertTrue(text.contains("环境变量：FAKE_TOKEN"), text);
        assertTrue(text.contains("免确认工具：echo"), text);
        assertFalse(text.contains("sk-secret-value"), text);
    }

    @Test
    void onceStartsServerRegistersPrefixedToolsAfterS13ToolsAndAddsRule() throws Exception {
        config(Map.of());
        StringWriter buffer = new StringWriter();
        TeamMain.Session session = session("once\nx\nexit\n", buffer, answers(new ChatMessage("assistant", "ok")), false);
        session.run();
        List<String> names = toolNames.get(0);
        assertEquals(33, names.size());
        assertEquals(List.of("mcp__fake__echo", "mcp__fake__add", "mcp__fake__fail", "mcp__fake__slow",
                "mcp__fake__exit", "mcp__fake__image"), names.subList(27, 33));
        assertEquals(ScheduleMain.systemPrompt(SkillCatalog.empty(), List.of()) + "\n\n" + McpManager.MCP_RULE,
                requests.get(0).get(0).content());
        assertTrue(buffer.toString().contains("MCP> fake 已连接，6 个工具：echo、add、fail、slow、exit、image"),
                buffer.toString());
        assertFalse(Files.exists(root.resolve("mcp-trust")), "once 不保存");
        assertFalse(session.mcp().servers().isEmpty());
        assertTrue(Files.readAllLines(record).contains("initialize"));
    }

    @Test
    void startupLogsEachStepInOrderAndShutdownIsLogged() throws Exception {
        config(Map.of("allow", List.of("echo")));
        StringWriter buffer = new StringWriter();
        session("once\nexit\n", buffer, answers(), false).run();
        String text = buffer.toString();
        List<String> steps = List.of(
                "MCP> 读取 ", "共 1 个 server：fake",
                "MCP 信任确认>",
                "MCP> [1/1] 正在启动 fake：" + FakeServers.java(),
                "MCP> fake 进程已启动（pid ",
                "MCP> fake 握手完成（fake 1，协议 " + McpClient.PROTOCOL_VERSION,
                "MCP> fake 正在获取工具列表…",
                "MCP> fake 已连接，6 个工具",
                "MCP> 启动完成：1 个 server 已连接，共 6 个工具，其中 1 个免确认。",
                "MCP> 已关闭 1 个 server：fake");
        int position = 0;
        for (String step : steps) {
            int found = text.indexOf(step, position);
            assertTrue(found >= 0, "缺少或顺序错误：" + step + "\n" + text);
            position = found;
        }
        // 启动期间 server 的 stderr 逐行显示。
        assertTrue(text.contains("MCP> fake stderr: fake server starting in mode normal"), text);
    }

    @Test
    void failedServerIsCountedInSummary() throws Exception {
        Map<String, Map<String, Object>> servers = new LinkedHashMap<>();
        servers.put("bad", Map.of("mode", "crash"));
        servers.put("fake", Map.of("record", record));
        FakeServers.writeConfig(project, servers);
        StringWriter buffer = new StringWriter();
        session("once\nexit\n", buffer, answers(), false).run();
        String text = buffer.toString();
        assertTrue(text.contains("MCP> [1/2] 正在启动 bad"), text);
        assertTrue(text.contains("MCP> bad stderr: missing API token"), text);
        assertTrue(text.contains("MCP> [2/2] 正在启动 fake"), text);
        assertTrue(text.contains("MCP> 启动完成：1 个 server 已连接，1 个失败，共 6 个工具，其中 0 个免确认。"), text);
    }

    @Test
    void rememberedTrustSkipsPromptUntilConfigChanges() throws Exception {
        config(Map.of());
        StringWriter first = new StringWriter();
        session("y\nexit\n", first, answers(), false).run();
        assertTrue(first.toString().contains("MCP 信任> 已保存授权："), first.toString());

        StringWriter second = new StringWriter();
        session("exit\n", second, answers(), false).run();
        assertFalse(second.toString().contains("MCP 信任确认>"), second.toString());
        assertTrue(second.toString().contains("MCP> fake 已连接"), second.toString());

        config(Map.of("timeout", 9));
        StringWriter third = new StringWriter();
        session("n\nexit\n", third, answers(), false).run();
        assertTrue(third.toString().contains("MCP 信任确认>"), third.toString());
    }

    @Test
    void scriptListedInTrustFilesInvalidatesTrustWhenChanged() throws Exception {
        Path script = Files.writeString(project.resolve("helper.txt"), "v1");
        config(Map.of("trust_files", List.of("helper.txt")));
        session("y\nexit\n", new StringWriter(), answers(), false).run();
        Files.writeString(script, "v2");
        StringWriter buffer = new StringWriter();
        session("n\nexit\n", buffer, answers(), false).run();
        assertTrue(buffer.toString().contains("MCP 信任确认>"), buffer.toString());
    }

    @Test
    void hookTrustAndMcpTrustAreSeparate() throws Exception {
        Files.createDirectories(project.resolve(".agents"));
        Files.writeString(project.resolve(".agents/hook.json"), """
                {"hooks":{"Stop":[{"hooks":[{"type":"command","command":"exit 0"}]}]}}""");
        config(Map.of());
        StringWriter buffer = new StringWriter();
        // 第一个回答给 Hook（拒绝），第二个给 MCP（仅本次）。
        session("n\nonce\nexit\n", buffer, answers(), false).run();
        String text = buffer.toString();
        assertTrue(text.indexOf("Hook 信任确认>") < text.indexOf("MCP 信任确认>"), text);
        assertTrue(text.contains("本次未启用用户 Hook"), text);
        assertTrue(text.contains("MCP> fake 已连接"), text);
    }

    @Test
    void callsAskForConfirmationByDefaultAndGoThroughHooks() throws Exception {
        config(Map.of());
        StringWriter buffer = new StringWriter();
        session("once\n用工具\ny\n再用\nn\nexit\n", buffer, answers(
                ChatMessage.assistant(null, List.of(call("c1", "mcp__fake__echo", Map.of("text", "第一次")))),
                new ChatMessage("assistant", "完成一"),
                ChatMessage.assistant(null, List.of(call("c2", "mcp__fake__echo", Map.of("text", "第二次")))),
                new ChatMessage("assistant", "完成二")), false).run();
        String text = buffer.toString();
        assertTrue(text.contains("权限确认> 将调用外部 MCP server 的工具"), text);
        assertEquals("第一次", toolResult("c1"));
        assertEquals("权限拒绝：用户未批准本次调用。 工具未执行。", toolResult("c2"));
        // 内置日志 Hook 记录了工具调用前后。
        assertTrue(text.contains("mcp__fake__echo"), text);
    }

    @Test
    void allowListedToolRunsWithoutConfirmationOthersStillAsk() throws Exception {
        config(Map.of("allow", List.of("add", "missing")));
        StringWriter buffer = new StringWriter();
        session("once\n算\nexit\n", buffer, answers(
                ChatMessage.assistant(null, List.of(call("c1", "mcp__fake__add", Map.of("a", 2, "b", 3)),
                        call("c2", "mcp__fake__echo", Map.of("text", "x")))),
                new ChatMessage("assistant", "完成")), false).run();
        String text = buffer.toString();
        assertTrue(text.contains("MCP> fake 没有工具 missing，allow 中的这一项无效。"), text);
        assertEquals("5.0", toolResult("c1"));
        // exit 作为确认回答不是 y，echo 被拒绝；随后输入结束。
        assertTrue(toolResult("c2").startsWith("权限拒绝："), toolResult("c2"));
        assertEquals(1, text.split("权限确认>", -1).length - 1, "只有 echo 需要确认");
    }

    @Test
    void toolErrorAndDisconnectBecomeToolResults() throws Exception {
        config(Map.of("allow", List.of("fail", "exit", "echo")));
        StringWriter buffer = new StringWriter();
        session("once\n试\nexit\n", buffer, answers(
                ChatMessage.assistant(null, List.of(call("c1", "mcp__fake__fail", Map.of()),
                        call("c2", "mcp__fake__exit", Map.of()),
                        call("c3", "mcp__fake__echo", Map.of("text", "x")))),
                new ChatMessage("assistant", "完成")), false).run();
        assertEquals("工具错误：磁盘已满", toolResult("c1"));
        assertTrue(toolResult("c2").startsWith("工具错误：文件或命令操作失败"), toolResult("c2"));
        assertTrue(toolResult("c3").startsWith("工具错误："), toolResult("c3"));
    }

    @Test
    void brokenServerIsSkippedAndOthersStillConnect() throws Exception {
        Map<String, Map<String, Object>> servers = new LinkedHashMap<>();
        servers.put("bad", Map.of("mode", "crash"));
        servers.put("missing", Map.of("command", "definitely-not-a-command-s18"));
        servers.put("fake", Map.of("record", record));
        FakeServers.writeConfig(project, servers);
        StringWriter buffer = new StringWriter();
        session("once\nx\nexit\n", buffer, answers(new ChatMessage("assistant", "ok")), false).run();
        String text = buffer.toString();
        assertTrue(text.contains("MCP> bad 启动失败，已跳过："), text);
        assertTrue(text.contains("stderr> "), text);
        assertTrue(text.contains("missing API token"), text);
        assertTrue(text.contains("MCP> missing 启动失败，已跳过：无法启动 definitely-not-a-command-s18"), text);
        assertTrue(text.contains("MCP> fake 已连接"), text);
        assertEquals(33, toolNames.get(0).size());
    }

    @Test
    void missingEnvironmentVariablePreventsThatServerOnly() throws Exception {
        config(Map.of("env", Map.of("FAKE_TOKEN", "${S15_UNSET_TOKEN}")));
        StringWriter buffer = new StringWriter();
        session("once\nexit\n", buffer, answers(), false).run();
        assertTrue(buffer.toString().contains("环境变量 S15_UNSET_TOKEN 未设置"), buffer.toString());
        assertFalse(Files.exists(record));
    }

    @Test
    void invalidConfigurationPreventsStartup() throws IOException {
        Files.createDirectories(project.resolve(".agents"));
        Files.writeString(project.resolve(McpConfig.DEFAULT_PATH), "{\"mcpServers\":{\"Bad Name\":{\"command\":\"x\"}}}");
        assertThrows(IllegalArgumentException.class,
                () -> session("exit\n", new StringWriter(), answers(), false));
    }

    @Test
    void subagentHasNoMcpTools() throws Exception {
        config(Map.of());
        StringWriter buffer = new StringWriter();
        session("once\n派\nexit\n", buffer, answers(
                ChatMessage.assistant(null, List.of(call("t1", "task", Map.of("description", "看看", "prompt", "做")))),
                new ChatMessage("assistant", "子代理完成"),
                new ChatMessage("assistant", "主代理完成")), false).run();
        List<String> child = toolNames.get(1);
        assertTrue(child.stream().noneMatch(name -> name.startsWith(McpTool.PREFIX)), child.toString());
        assertTrue(toolNames.get(0).stream().anyMatch(name -> name.startsWith(McpTool.PREFIX)));
    }

    @Test
    void teamModeLeadHasMcpToolsAndRuleAfterTeamRule() throws Exception {
        config(Map.of());
        StringWriter buffer = new StringWriter();
        TeamMain.Session session = session("once\nx\nexit\n", buffer, answers(new ChatMessage("assistant", "ok")), true);
        session.run();
        List<String> names = toolNames.get(0);
        assertEquals(37, names.size());
        assertEquals("mcp__fake__echo", names.get(31));
        assertEquals(TeamMain.systemPrompt(SkillCatalog.empty(), List.of()) + "\n\n" + McpManager.MCP_RULE,
                requests.get(0).get(0).content());
    }

    @Test
    void sessionEndClosesServers() throws Exception {
        config(Map.of());
        TeamMain.Session session = session("once\nexit\n", new StringWriter(), answers(), false);
        assertEquals(List.of("fake"), session.mcp().servers());
        session.run();
        McpTool tool = (McpTool) session.mcp().tools().get(0);
        IOException error = assertThrows(IOException.class, () -> tool.execute("{\"text\":\"x\"}"));
        assertTrue(error.getMessage().contains("客户端已关闭"), error.getMessage());
    }

    @Test
    void statusReportsConnectionToolsAndAllowListForSlashCommand() throws Exception {
        config(Map.of("allow", List.of("add")));
        StringWriter buffer = new StringWriter();
        TeamMain.Session session = session("once\nexit\n", buffer, answers(), false);
        List<McpManager.ServerStatus> status = session.mcp().status();
        assertEquals(1, status.size());
        assertEquals("fake", status.get(0).name());
        assertTrue(status.get(0).connected());
        assertEquals(List.of("echo", "add", "fail", "slow", "exit", "image"), status.get(0).tools());
        assertEquals(List.of("add"), status.get(0).allowed());
        session.run();
        assertFalse(session.mcp().status().get(0).connected(), "会话结束关闭后显示已断开");
        assertTrue(McpManager.none().status().isEmpty());
    }

    private void config(Map<String, Object> options) throws IOException {
        Map<String, Object> fake = new LinkedHashMap<>(options);
        fake.put("record", record);
        FakeServers.writeConfig(project, Map.of("fake", fake));
    }

    private String toolResult(String id) {
        for (List<ChatMessage> messages : requests) {
            for (ChatMessage message : messages) {
                if ("tool".equals(message.role()) && id.equals(message.toolCallId())) {
                    return message.content();
                }
            }
        }
        fail("没有找到 " + id + " 的工具结果");
        return null;
    }

    private TeamMain.Session session(String input, StringWriter buffer, ModelClient client, boolean team)
            throws IOException {
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake", work, 6,
                Duration.ofSeconds(10), 12000, 50000, 3, Duration.ofSeconds(30)).withTeamEnabled(team);
        return McpMain.createSession(config, client, new BufferedReader(new StringReader(input)),
                new PrintWriter(buffer, true), project, List.of(), new HookTrustStore(root.resolve("hook-trust")),
                new McpTrustStore(root.resolve("mcp-trust")), Map.of(), new TodoList(), ZoneOffset.UTC,
                Clock.fixed(Instant.parse("2026-01-01T00:00:30Z"), ZoneOffset.UTC), false, Duration.ofSeconds(5));
    }

    private ModelClient answers(ChatMessage... replies) {
        return new ModelClient() {
            int next;

            @Override
            public synchronized ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                toolNames.add(tools.stream().map(ToolDefinition::name).toList());
                requests.add(new ArrayList<>(messages));
                return next < replies.length ? replies[next++] : new ChatMessage("assistant", "完成");
            }
        };
    }

    private static ToolCall call(String id, String name, Map<String, ?> args) {
        return new ToolCall(id, name, JSON.toJSONString(args));
    }
}
