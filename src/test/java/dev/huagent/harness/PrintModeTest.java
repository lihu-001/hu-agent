package dev.huagent.harness;

import dev.huagent.app.TeamMain;
import dev.huagent.command.CommandStarter;
import dev.huagent.config.AgentConfig;
import dev.huagent.hook.HookConfig;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.mcp.McpManager;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.permission.AutoApprove;
import dev.huagent.permission.ConfirmPrompt;
import dev.huagent.todo.TodoList;
import dev.huagent.tool.ToolCall;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** hu -p 的装配（与 HuMain.print 相同）：任务只跑一轮，最终回答单独输出，过程不重复最终回答，确认没有人回答。 */
class PrintModeTest {
    @TempDir
    Path root;
    private Path work;
    private Path project;
    private final List<List<ChatMessage>> requests = new ArrayList<>();
    private final StringWriter result = new StringWriter();
    private final StringWriter progress = new StringWriter();

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(root.resolve("work"));
        project = Files.createDirectories(root.resolve("project"));
        Files.writeString(work.resolve("notes.txt"), "第 1 行\n第 2 行");
    }

    @Test
    void finalAnswerGoesToResultAndProgressShowsTheRest() throws Exception {
        int code = run("读一下 notes.txt\n再总结", true, (messages, tools) -> requests.size() == 1
                ? ChatMessage.assistant("先读一下", List.of(new ToolCall("r1", "read_file", "{\"path\":\"notes.txt\"}")))
                : new ChatMessage("assistant", "读完了，共两行"));
        assertEquals(0, code, progress.toString());
        assertEquals("读完了，共两行", result.toString().strip());
        assertEquals(2, requests.size(), "只跑一轮");
        assertEquals("读一下 notes.txt\n再总结", requests.get(0).getLast().content(), "多行任务作为一条消息");
        String log = progress.toString();
        int intent = log.indexOf("助手> 先读一下");
        int tool = log.indexOf("工具> read_file notes.txt · 2 行");
        assertTrue(intent >= 0 && intent < tool, log);
        assertFalse(log.contains("读完了"), "最终回答不在过程里重复\n" + log);
        assertFalse(log.contains("你> "), log);
    }

    @Test
    void failedRequestHasNoAnswer() throws Exception {
        int code = run("任务", true, (messages, tools) -> {
            throw new IOException("连接被拒绝");
        });
        assertEquals(1, code);
        assertEquals("", result.toString());
        assertTrue(progress.toString().contains("请求失败：连接被拒绝"), progress.toString());
        assertTrue(progress.toString().contains("hu: 本轮没有得到最终回答"), progress.toString());
        assertEquals(1, requests.size(), "失败后不再读到输入");
    }

    @Test
    void confirmationsAreDeniedWithoutAutoApproveAndAllowedWithIt() throws Exception {
        for (boolean autoApprove : List.of(false, true)) {
            requests.clear();
            Path file = work.resolve("out.txt");
            Files.deleteIfExists(file);
            int code = run("写文件", autoApprove, (messages, tools) -> requests.size() == 1
                    ? ChatMessage.assistant(null,
                            List.of(new ToolCall("w1", "write_file", "{\"path\":\"out.txt\",\"content\":\"x\"}")))
                    : new ChatMessage("assistant", "完成"));
            assertEquals(0, code);
            assertEquals(autoApprove, Files.exists(file), progress.toString());
            String toolResult = requests.get(1).getLast().content();
            assertEquals(!autoApprove, toolResult.contains("确认输入已结束，未获得本次授权"), toolResult);
        }
    }

    @Test
    void untrustedProjectHooksAreDeclinedWithoutConsumingTheTask() throws Exception {
        // 信任后会拦下所有提示词的 Hook：确认读不到回答，Hook 不启用，任务照常执行。
        Files.createDirectories(project.resolve(".agents"));
        Files.writeString(project.resolve(HookConfig.DEFAULT_PATH), JSON.toJSONString(Map.of("hooks",
                Map.of("UserPromptSubmit", List.of(Map.of("hooks",
                        List.of(Map.of("type", "command", "command", "exit 2"))))))));
        int code = run("任务", true, (messages, tools) -> new ChatMessage("assistant", "好的"));
        assertEquals(0, code, progress.toString());
        assertEquals("好的", result.toString().strip());
        assertTrue(progress.toString().contains("本次未启用用户 Hook"), progress.toString());
        assertEquals("任务", requests.get(0).getLast().content());
    }

    private int run(String task, boolean autoApprove, ModelClient model) throws IOException {
        ModelClient client = (messages, tools) -> {
            requests.add(messages);
            return model.complete(messages, tools);
        };
        PrintMode mode = new PrintMode(task);
        PrintWriter log = new PrintWriter(progress, true);
        HarnessOptions options = new HarnessOptions(new EventHub(), new ModelRetryPolicy(0), mode.input(), false,
                out -> { }, ConfirmPrompt.of(false, false), new AutoApprove(autoApprove), new TurnInterrupt(), null,
                Transcript.headless(), null);
        TeamMain.Session session = TeamMain.createSession(
                new AgentConfig("http://localhost", "unused", "fake", work, 6, Duration.ofSeconds(10), 12000),
                client, PrintMode.noAnswers(), log, project, List.of(mode),
                new HookTrustStore(root.resolve("trust")), new TodoList(), ZoneOffset.UTC,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), false, Duration.ofSeconds(5),
                McpManager.Starter.none(), options, null, null, CommandStarter.builtin());
        session.run();
        return mode.finish(new PrintWriter(result, true), log);
    }
}
