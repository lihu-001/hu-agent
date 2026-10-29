package dev.huagent.compact;

import dev.huagent.app.CompactMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.hook.AgentHook;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.todo.TodoItem;
import dev.huagent.todo.TodoList;
import dev.huagent.todo.TodoStatus;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CompactLoopTest {
    @TempDir
    Path root;
    private Path work;
    private final List<List<ChatMessage>> agentRequests = new ArrayList<>();
    private final List<List<ChatMessage>> summaryRequests = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(root.resolve("work"));
        Files.writeString(work.resolve("big.txt"), "B".repeat(400));
    }

    @Test
    void registersCompactToolAndMentionsItInSystemPrompt() throws Exception {
        run("x\nexit\n", 100000, 3, (messages, tools) -> {
            assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob", "grep",
                    "todo_write", "task", "load_skill", "compact"), tools.stream().map(ToolDefinition::name).toList());
            assertTrue(messages.get(0).content().contains(CompactMain.COMPACT_RULE));
            return new ChatMessage("assistant", "ok");
        });
    }

    @Test
    void microCompactAffectsRequestButNextTurnStillHasOriginalInHistory() throws Exception {
        // 第一轮读两次大文件，第二轮直接回答；保留最近 1 个工具结果。
        // 阈值 1200：第三次请求时对话约 870 字符，超过阈值一半才微压缩，又不到阈值，不会总结。
        String output = run("读两次\n再问\nexit\n", 1200, 1, script(
                ChatMessage.assistant(null, List.of(read("r1"))),
                ChatMessage.assistant(null, List.of(read("r2"))),
                new ChatMessage("assistant", "读完"),
                new ChatMessage("assistant", "第二轮")));
        List<ChatMessage> third = agentRequests.get(2);
        assertTrue(contentOf(third, "r1").startsWith(MicroCompact.PLACEHOLDER_PREFIX), contentOf(third, "r1"));
        assertTrue(contentOf(third, "r2").contains("B".repeat(400)));
        assertTrue(output.contains("压缩> 微压缩"), output);
        assertTrue(summaryRequests.isEmpty());
        assertEquals(List.of(), transcripts());
    }

    @Test
    void autoCompactMidTurnKeepsToolProtocolValidAndLaterTurnsBuildOnSummary() throws Exception {
        String output = run("读文件\n下一个问题\nexit\n", 300, 3, script(
                ChatMessage.assistant(null, List.of(read("r1"))),
                new ChatMessage("assistant", "第一轮完成"),
                new ChatMessage("assistant", "第二轮完成")));
        // 第一次请求很小；工具结果加入后超过 300 字符，第二次请求前总结。
        assertEquals(1, summaryRequests.size());
        List<ChatMessage> second = agentRequests.get(1);
        assertEquals(List.of("system", "user"), second.stream().map(ChatMessage::role).toList());
        assertTrue(second.get(1).content().startsWith(ContextCompactor.SUMMARY_MARKER));
        assertTrue(second.get(1).content().endsWith("用户最新输入（原文）：\n读文件"));
        // 第二轮在摘要之上继续，没有残留未配对的 tool 消息。
        List<ChatMessage> third = agentRequests.get(2);
        assertEquals(List.of("system", "user", "assistant", "user"), third.stream().map(ChatMessage::role).toList());
        assertEquals("下一个问题", third.get(3).content());
        assertTrue(output.contains("压缩> 完成"), output);
        assertEquals(1, transcripts().size());
    }

    @Test
    void compactToolTriggersAfterBatchAndTodoStateSurvives() throws Exception {
        TodoList todos = new TodoList();
        todos.replace(List.of(new TodoItem("写测试", TodoStatus.IN_PROGRESS)));
        List<String> events = new ArrayList<>();
        AgentHook hook = new AgentHook() {
            @Override
            public String preToolUse(ToolCall call) {
                events.add("pre:" + call.name());
                return null;
            }
        };
        String output = run("压缩一下\nexit\n", 100000, 3, todos, script(
                ChatMessage.assistant(null, List.of(new ToolCall("c1", "compact",
                        JSON.toJSONString(Map.of("focus", "路径"))), read("r1"))),
                new ChatMessage("assistant", "已压缩")), hook);
        assertEquals(List.of("pre:compact", "pre:read_file"), events, "compact 经过 Hook，同批工具都执行");
        assertFalse(output.contains("[y/N]"), "compact 免确认");
        assertEquals(1, summaryRequests.size());
        assertTrue(summaryRequests.get(0).get(0).content().endsWith("本次压缩请特别保留：路径"));
        // 总结输入包含同批工具结果，说明压缩在整批结果补齐后才执行。
        assertTrue(summaryRequests.get(0).get(1).content().contains("[tool] 已安排压缩"));
        String summary = agentRequests.get(1).get(1).content();
        assertTrue(summary.contains("[>] 写测试"), summary);
    }

    @Test
    void hookCanBlockCompactTool() throws Exception {
        AgentHook hook = new AgentHook() {
            @Override
            public String preToolUse(ToolCall call) {
                return "compact".equals(call.name()) ? "不许压缩" : null;
            }
        };
        run("x\nexit\n", 100000, 3, new TodoList(), script(
                ChatMessage.assistant(null, List.of(new ToolCall("c1", "compact", "{}"))),
                new ChatMessage("assistant", "好")), hook);
        assertTrue(summaryRequests.isEmpty());
        assertTrue(contentOf(agentRequests.get(1), "c1").startsWith("钩子拒绝：不许压缩"));
    }

    @Test
    void summaryRequestDoesNotCountTowardModelCallLimit() throws Exception {
        // 上限 2 次：第一次调用工具，第二次前自动压缩，第二次仍能给出最终回复。
        String output = run("读文件\nexit\n", 300, 3, script(
                ChatMessage.assistant(null, List.of(read("r1"))),
                new ChatMessage("assistant", "完成")), 2);
        assertEquals(2, agentRequests.size());
        assertTrue(output.contains("助手> 完成"), output);
        assertFalse(output.contains("已达到本轮模型调用上限"), output);
    }

    private ModelClient script(ChatMessage... answers) {
        return new ModelClient() {
            int next;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                if (messages.get(0).content().startsWith(ContextCompactor.SUMMARIZER_PROMPT)) {
                    summaryRequests.add(messages);
                    return new ChatMessage("assistant", "测试摘要");
                }
                agentRequests.add(messages);
                return answers[next++];
            }
        };
    }

    private String run(String text, int threshold, int keep, ModelClient client) throws IOException {
        return run(text, threshold, keep, new TodoList(), client);
    }

    private String run(String text, int threshold, int keep, ModelClient client, int maxCalls) throws IOException {
        return run(text, threshold, keep, new TodoList(), client, maxCalls);
    }

    private String run(String text, int threshold, int keep, TodoList todos, ModelClient client,
                       AgentHook... hooks) throws IOException {
        return run(text, threshold, keep, todos, client, 5, hooks);
    }

    private String run(String text, int threshold, int keep, TodoList todos, ModelClient client, int maxCalls,
                       AgentHook... hooks) throws IOException {
        BufferedReader input = new BufferedReader(new StringReader(text));
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake", work, maxCalls,
                Duration.ofSeconds(5), 12000, threshold, keep);
        // 临时项目目录没有 hook.json 和技能，信任存储也在临时目录。
        CompactMain.createLoop(config, client, input, output, Files.createDirectories(root.resolve("project")),
                List.of(hooks), new HookTrustStore(root.resolve("trust")), todos).run(input, output);
        return buffer.toString();
    }

    private List<Path> transcripts() throws IOException {
        Path directory = work.resolve(TranscriptStore.DIRECTORY_NAME);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.toList();
        }
    }

    private static ToolCall read(String id) {
        return new ToolCall(id, "read_file", JSON.toJSONString(Map.of("path", "big.txt")));
    }

    private static String contentOf(List<ChatMessage> messages, String callId) {
        return messages.stream().filter(m -> callId.equals(m.toolCallId())).findFirst().orElseThrow().content();
    }
}
