package dev.huagent.harness;

import dev.huagent.app.TeamMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.mcp.McpManager;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.todo.TodoList;
import dev.huagent.tool.ToolCall;
import dev.huagent.workflow.WorkflowStarter;
import dev.huagent.workflow.WorkflowStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** hu 的完整装配：默认（非 debug）终端只显示最终回答，模型收到的内容不变；debug 与教程入口的完整输出相同。 */
class DebugModeTest {
    private static final String FILE = lines(10);
    @TempDir
    Path root;
    private Path work;
    private Path project;
    private final List<List<ChatMessage>> requests = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(root.resolve("work"));
        project = Files.createDirectories(root.resolve("project"));
        Files.writeString(work.resolve("notes.txt"), FILE);
    }

    @Test
    void defaultShowsRepliesAndOneLinePerToolCallButModelGetsFullResult() throws Exception {
        String output = run(false);
        for (String detail : List.of("技能> 已加载", "记忆> 已加载", "任务> .tasks", "工作流> .workflows", "计划> 已加载",
                "对话已启动", "工具> read_file：", "工具请求>", "工具结果>", "第 1 行", "模型请求>", "模型响应>",
                "钩子 ", "[r1]")) {
            assertFalse(output.contains(detail), detail + "\n" + output);
        }
        // 与 Claude Code 相同：中间说明、每次工具调用的一行摘要和最终回答依次显示。
        int intent = output.indexOf("助手> 先读一下");
        int tool = output.indexOf("工具> read_file notes.txt · 10 行");
        int answer = output.indexOf("助手> 读完了");
        assertTrue(intent >= 0 && intent < tool && tool < answer, output);
        ChatMessage result = requests.get(1).get(requests.get(1).size() - 1);
        assertEquals("r1", result.toolCallId());
        assertTrue(result.content().startsWith("     1\t第 1 行\n") && result.content().endsWith("    10\t第 10 行"),
                "模型收到完整结果");
    }

    @Test
    void debugShowsRunDetails() throws Exception {
        String output = run(true);
        for (String detail : List.of("技能> 已加载", "工作流> .workflows 中有 0 个工作流", "计划> 已加载 0 个计划",
                "对话已启动", "工具> read_file：", "模型请求> 第 1 次请求中",
                "模型响应> 耗时", "钩子 PreToolUse> read_file [r1]", "助手> 先读一下", "工具请求> read_file [r1]",
                "工具结果> [r1]      1\t第 1 行")) {
            assertTrue(output.contains(detail), detail + "\n" + output);
        }
    }

    private String run(boolean debug) throws IOException {
        ModelClient client = (messages, tools) -> {
            requests.add(messages);
            return requests.size() == 1
                    ? ChatMessage.assistant("先读一下", List.of(new ToolCall("r1", "read_file", "{\"path\":\"notes.txt\"}")))
                    : new ChatMessage("assistant", "读完了");
        };
        HarnessOptions options = new HarnessOptions(new EventHub(), new ModelRetryPolicy(0),
                new BufferedReader(new StringReader("读一下 notes.txt\nexit\n")), debug);
        StringWriter buffer = new StringWriter();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        TeamMain.Session session = TeamMain.createSession(
                new AgentConfig("http://localhost", "unused", "fake", work, 6, Duration.ofSeconds(10), 12000),
                client, new BufferedReader(new StringReader("")), new PrintWriter(buffer, true), project, List.of(),
                new HookTrustStore(root.resolve("trust")), new TodoList(), ZoneOffset.UTC,
                Clock.fixed(now, ZoneOffset.UTC), true, Duration.ofSeconds(5), McpManager.Starter.none(), options,
                WorkflowStarter.of(work.resolve(WorkflowStore.DIRECTORY_NAME)));
        session.run();
        return buffer.toString();
    }

    private static String lines(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "第 " + i + " 行").collect(Collectors.joining("\n"));
    }
}
