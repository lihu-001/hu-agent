package dev.huagent.background;

import dev.huagent.app.BackgroundMain;
import dev.huagent.app.TaskMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.hook.AgentHook;
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
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundLoopTest {
    @TempDir
    Path root;
    private Path work;
    private final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    private final List<List<ChatMessage>> requests = new ArrayList<>();
    private final List<List<String>> toolNames = new ArrayList<>();
    private StringWriter buffer;

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(root.resolve("work"));
    }

    @Test
    void registersBackgroundToolsAndPromptAddsRule() throws Exception {
        run("x\nexit\n", script(new ChatMessage("assistant", "ok")));
        List<String> names = toolNames.get(0);
        assertEquals(List.of("background_run", "background_check"), names.subList(names.size() - 2, names.size()));
        assertEquals(20, names.size());
        assertEquals(TaskMain.systemPrompt(SkillCatalog.empty(), List.of()) + "\n\n" + BackgroundMain.BACKGROUND_RULE,
                requests.get(0).get(0).content());
    }

    @Test
    void completionIsDeliveredOnceAppendedToLatestMessageWithoutNewRole() throws Exception {
        String command = windows ? "Write-Output built" : "echo built";
        String output = run("构建\ny\n下一步\nexit\n", new ModelClient() {
            int next;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws InterruptedException {
                requests.add(messages);
                switch (next++) {
                    case 0:
                        return ChatMessage.assistant(null, List.of(call("b1", "background_run", Map.of("command", command))));
                    case 1:
                        assertTrue(contentOf(messages, "b1").startsWith("已在后台启动 bg_1"), contentOf(messages, "b1"));
                        // 主循环没有等待命令；模型在这里先结束本轮，命令完成后才有下一轮输入。
                        awaitOutput("后台> bg_1 completed");
                        return new ChatMessage("assistant", "已在后台构建");
                    case 2:
                        return new ChatMessage("assistant", "构建好了");
                    default:
                        throw new AssertionError();
                }
            }
        });
        assertTrue(output.contains("[y/N]"), "background_run 与 shell 一样逐次确认");
        // 通知可能在第二次请求（工具结果之后）或下一轮用户输入时送达，但只送达一次且附在已有消息末尾。
        // 系统提示词的规则里也提到标记，只统计对话消息。
        List<ChatMessage> last = requests.get(2).subList(1, requests.get(2).size());
        long carriers = last.stream().filter(m -> m.content() != null
                && m.content().contains(BackgroundManager.NOTIFICATION_START)).count();
        assertEquals(1, carriers, "通知保留在历史中且只出现一次");
        ChatMessage carrier = last.stream().filter(m -> m.content() != null
                && m.content().contains(BackgroundManager.NOTIFICATION_START)).findFirst().orElseThrow();
        assertTrue(List.of("user", "tool").contains(carrier.role()), carrier.role());
        assertTrue(carrier.content().contains("[bg_1] completed") && carrier.content().contains("built"), carrier.content());
        assertTrue(last.stream().noneMatch(m -> "system".equals(m.role())), "不新增 system 消息");
        assertTrue(output.contains("后台> 已将完成结果交给模型"), output);
    }

    @Test
    void notificationAttachesToToolResultWhileModelKeepsWorking() throws Exception {
        String command = windows ? "Write-Output done" : "echo done";
        run("构建\ny\nexit\n", new ModelClient() {
            int next;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws InterruptedException {
                requests.add(messages);
                switch (next++) {
                    case 0:
                        return ChatMessage.assistant(null, List.of(call("b1", "background_run", Map.of("command", command))));
                    case 1:
                        awaitOutput("后台> bg_1 completed");
                        return ChatMessage.assistant(null, List.of(new ToolCall("g1", "glob", "{\"pattern\":\"*\"}")));
                    case 2:
                        return new ChatMessage("assistant", "完成");
                    default:
                        throw new AssertionError();
                }
            }
        });
        boolean early = requests.get(1).get(requests.get(1).size() - 1).content()
                .contains(BackgroundManager.NOTIFICATION_START);
        ChatMessage tail = requests.get(2).get(requests.get(2).size() - 1);
        assertEquals("tool", tail.role());
        if (!early) {
            assertEquals("g1", tail.toolCallId(), "附在本批最后一个工具结果上，tool_call_id 不变");
            assertTrue(tail.content().contains("[bg_1] completed"), tail.content());
        }
    }

    @Test
    void deniedBackgroundRunDoesNotStart() throws Exception {
        run("构建\nn\nexit\n", script(
                ChatMessage.assistant(null, List.of(call("b1", "background_run",
                        Map.of("command", windows ? "Set-Content made.txt x" : "echo x > made.txt")))),
                ChatMessage.assistant(null, List.of(new ToolCall("c1", "background_check", "{}"))),
                new ChatMessage("assistant", "好")));
        assertTrue(contentOf(requests.get(1), "b1").startsWith("权限拒绝：用户未批准"), contentOf(requests.get(1), "b1"));
        assertEquals("本次会话没有后台任务。", contentOf(requests.get(2), "c1"));
        assertFalse(Files.exists(work.resolve("made.txt")));
    }

    @Test
    void hardDenyRulesApplyToBackgroundRun() throws Exception {
        String output = run("x\nexit\n", script(
                ChatMessage.assistant(null, List.of(call("b1", "background_run", Map.of("command", "shutdown /s")))),
                new ChatMessage("assistant", "好")));
        assertFalse(output.contains("[y/N]"));
        assertTrue(contentOf(requests.get(1), "b1").startsWith("权限拒绝：命中提权"), contentOf(requests.get(1), "b1"));
    }

    @Test
    void exitCancelsRunningTasks() throws Exception {
        String command = windows ? "Start-Sleep -Seconds 20; Set-Content late.txt done" : "sleep 20; echo done > late.txt";
        long started = System.nanoTime();
        String output = run("构建\ny\nexit\n", script(
                ChatMessage.assistant(null, List.of(call("b1", "background_run", Map.of("command", command)))),
                new ChatMessage("assistant", "已启动")));
        assertTrue(output.contains("后台> 会话结束，已取消 1 个仍在运行的后台任务。"), output);
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(15).toNanos());
        Thread.sleep(500);
        assertFalse(Files.exists(work.resolve("late.txt")));
    }

    @Test
    void backgroundToolsPassThroughHooks() throws Exception {
        List<String> events = new ArrayList<>();
        AgentHook hook = new AgentHook() {
            @Override
            public String preToolUse(ToolCall call) {
                events.add("pre:" + call.name());
                return "background_run".equals(call.name()) ? "禁止后台" : null;
            }

            @Override
            public void postToolUse(ToolCall call, String result, long elapsedNanos) {
                events.add("post:" + call.name());
            }
        };
        String output = run("x\nexit\n", script(
                ChatMessage.assistant(null, List.of(new ToolCall("c1", "background_check", "{}"),
                        call("b1", "background_run", Map.of("command", "echo x")))),
                new ChatMessage("assistant", "好")), hook);
        assertEquals(List.of("pre:background_check", "post:background_check", "pre:background_run"), events);
        assertTrue(contentOf(requests.get(1), "b1").startsWith("钩子拒绝：禁止后台"));
        assertFalse(output.contains("[y/N]"), "Hook 拦截发生在权限确认之前");
    }

    @Test
    void noticeIsRequeuedWhenFirstRequestOfTurnFails() throws Exception {
        String command = windows ? "Write-Output built" : "echo built";
        String output = run("构建\ny\n第二轮\n第三轮\nexit\n", new ModelClient() {
            int next;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools)
                    throws IOException, InterruptedException {
                requests.add(messages);
                switch (next++) {
                    case 0:
                        return ChatMessage.assistant(null, List.of(call("b1", "background_run", Map.of("command", command))));
                    case 1:
                        if (!messages.get(messages.size() - 1).content().contains(BackgroundManager.NOTIFICATION_START)) {
                            awaitOutput("后台> bg_1 completed");
                        }
                        return new ChatMessage("assistant", "已启动");
                    case 2:
                        throw new IOException("网络断开");
                    case 3:
                        return new ChatMessage("assistant", "好");
                    default:
                        throw new AssertionError();
                }
            }
        });
        assertTrue(output.contains("请求失败：网络断开；本轮未保存"), output);
        boolean deliveredEarly = requests.get(1).get(requests.get(1).size() - 1).content()
                .contains(BackgroundManager.NOTIFICATION_START);
        if (!deliveredEarly) {
            ChatMessage failed = requests.get(2).get(requests.get(2).size() - 1);
            assertTrue(failed.content().startsWith("第二轮") && failed.content().contains("[bg_1] completed"));
            ChatMessage retried = requests.get(3).get(requests.get(3).size() - 1);
            assertTrue(retried.content().startsWith("第三轮") && retried.content().contains("[bg_1] completed"),
                    "失败轮被丢弃，通知放回后随下一轮送达：" + retried.content());
            assertEquals(1, requests.get(3).stream().filter(m -> !"system".equals(m.role()) && m.content() != null
                    && m.content().contains(BackgroundManager.NOTIFICATION_START)).count());
        }
    }

    @Test
    void subagentHasNoBackgroundTools() throws Exception {
        run("派个子任务\nexit\n", script(
                ChatMessage.assistant(null, List.of(call("t1", "task", Map.of("description", "看看", "prompt", "随便看看")))),
                new ChatMessage("assistant", "子代理总结"),
                new ChatMessage("assistant", "完成")));
        assertEquals(List.of("shell", "read_file", "write_file", "edit_file", "glob", "load_skill"),
                toolNames.get(1));
    }

    private void awaitOutput(String text) throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            if (buffer.toString().contains(text)) {
                return;
            }
            Thread.sleep(50);
        }
        fail("没有等到：" + text + "\n" + buffer);
    }

    private static ToolCall call(String id, String name, Map<String, ?> args) {
        return new ToolCall(id, name, JSON.toJSONString(args));
    }

    private ModelClient script(ChatMessage... answers) {
        return new ModelClient() {
            int next;

            @Override
            public ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
                requests.add(messages);
                toolNames.add(tools.stream().map(ToolDefinition::name).toList());
                return answers[next++];
            }
        };
    }

    private String run(String text, ModelClient client, AgentHook... hooks) throws IOException {
        BufferedReader input = new BufferedReader(new StringReader(text));
        buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake", work, 6,
                Duration.ofSeconds(5), 12000, 50000, 3, Duration.ofSeconds(30));
        // 临时项目目录没有 hook.json 和技能，信任存储也在临时目录。
        BackgroundMain.createLoop(config, client, input, output, Files.createDirectories(root.resolve("project")),
                List.of(hooks), new HookTrustStore(root.resolve("trust")), new TodoList()).run(input, output);
        return buffer.toString();
    }

    private static String contentOf(List<ChatMessage> messages, String callId) {
        return messages.stream().filter(m -> callId.equals(m.toolCallId())).findFirst().orElseThrow().content();
    }
}
