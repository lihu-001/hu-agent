package dev.huagent.hook;

import com.alibaba.fastjson2.JSON;
import dev.huagent.app.HooksMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.model.ChatMessage;
import dev.huagent.tool.ToolCall;
import org.junit.jupiter.api.Test;
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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HookTrustTest {
    @TempDir
    Path root;
    private final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

    @Test
    void missingAndEmptyConfigurationNeverPromptOrConsumeInput() throws Exception {
        Files.createDirectories(project());
        for (boolean empty : List.of(false, true)) {
            if (empty) {
                config(Map.of());
            }
            BufferedReader input = input("真正的提示词\n");
            StringWriter output = new StringWriter();
            assertTrue(HookTrust.load(project(), input, new PrintWriter(output), store()).isEmpty());
            assertEquals("真正的提示词", input.readLine());
            assertTrue(output.toString().isEmpty());
            assertFalse(Files.exists(root.resolve("user")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"n\n", "\n", "unexpected\n", ""})
    void refusalOrEofDoesNotRunCommandsOrPersistTrust(String answer) throws Exception {
        simpleConfig(markerCommand());
        StringWriter output = new StringWriter();
        assertTrue(load(answer, output).isEmpty());
        assertTrue(output.toString().contains("本次未启用用户 Hook"));
        assertFalse(Files.exists(project().resolve("executed.txt")));
        assertFalse(Files.exists(root.resolve("user")));
    }

    @Test
    void onceEnablesOnlyThisSessionAndAsksAgainNextTime() throws Exception {
        simpleConfig(markerCommand());
        StringWriter output = new StringWriter();
        List<AgentHook> callbacks = load("once\n", output);
        assertEquals(1, callbacks.size());
        assertFalse(Files.exists(project().resolve("executed.txt")), "授权阶段不运行命令");
        new AgentHooks(callbacks, message -> fail(message)).userPromptSubmit("hello", List.of());
        assertTrue(Files.exists(project().resolve("executed.txt")));
        assertFalse(Files.exists(root.resolve("user")));
        StringWriter next = new StringWriter();
        assertTrue(load("n\n", next).isEmpty());
        assertTrue(next.toString().contains("Hook 信任确认>"));
    }

    @Test
    void rememberedTrustIsScopedToProjectAndDoesNotConsumeNextPrompt() throws Exception {
        simpleConfig("exit 0");
        assertEquals(1, load("yes\n", new StringWriter()).size());
        Path record = store().recordFile(project().toRealPath());
        assertTrue(Files.exists(record));
        assertFalse(record.startsWith(project()));
        BufferedReader input = input("正常输入\n");
        StringWriter output = new StringWriter();
        assertEquals(1, HookTrust.load(project(), input, new PrintWriter(output), store()).size());
        assertEquals("正常输入", input.readLine());
        assertFalse(output.toString().contains("Hook 信任确认>"));
        assertFalse(Files.readString(record).contains("exit 0"), "信任记录不保存命令正文");

        Path otherProject = root.resolve("other-project");
        Files.createDirectories(otherProject.resolve(".agents"));
        Files.copy(project().resolve(HookConfig.DEFAULT_PATH), otherProject.resolve(HookConfig.DEFAULT_PATH));
        assertTrue(HookTrust.load(otherProject, input("n\n"),
                new PrintWriter(new StringWriter()), store()).isEmpty());
    }

    @Test
    void configurationChangeRequiresNewApproval() throws Exception {
        simpleConfig("exit 0");
        load("y\n", new StringWriter());
        simpleConfig("exit 2");
        StringWriter output = new StringWriter();
        assertTrue(load("n\n", output).isEmpty());
        assertTrue(output.toString().contains("Hook 信任确认>"));
        assertTrue(output.toString().contains("exit 2"));
    }

    @Test
    void scriptContentChangeIsDetectedEvenWithSameSizeAndTimestamp() throws Exception {
        Path script = script("scripts/中文 name.py", "print('A')");
        simpleConfig("python \"scripts/中文 name.py\"");
        load("y\n", new StringWriter());
        FileTime timestamp = Files.getLastModifiedTime(script);
        Files.writeString(script, "print('B')");
        Files.setLastModifiedTime(script, timestamp);
        StringWriter output = new StringWriter();
        assertTrue(load("n\n", output).isEmpty());
        assertTrue(output.toString().contains("校验文件>"));
    }

    @Test
    void explicitTrustFilesTrackDependenciesNotPresentInCommand() throws Exception {
        Path dependency = script("rules/policy.txt", "allow");
        config(Map.of("UserPromptSubmit", List.of(Map.of("hooks", List.of(Map.of(
                "type", "command", "command", "exit 0", "trust_files", List.of("rules/policy.txt")))))));
        load("y\n", new StringWriter());
        Files.writeString(dependency, "deny");
        assertTrue(load("n\n", new StringWriter()).isEmpty());
    }

    @Test
    void missingScriptDisablesUserHooksWithoutAskingOrSaving() throws Exception {
        simpleConfig("python missing.py");
        BufferedReader input = input("正常输入\n");
        StringWriter output = new StringWriter();
        assertTrue(HookTrust.load(project(), input, new PrintWriter(output), store()).isEmpty());
        assertEquals("正常输入", input.readLine());
        assertTrue(output.toString().contains("无法校验"));
        assertFalse(Files.exists(root.resolve("user")));
    }

    @Test
    void corruptedOrRemovedTrustRecordRequiresApprovalAgain() throws Exception {
        simpleConfig("exit 0");
        load("y\n", new StringWriter());
        Path record = store().recordFile(project().toRealPath());
        Files.writeString(record, "not-json");
        assertTrue(load("n\n", new StringWriter()).isEmpty());
        load("y\n", new StringWriter());
        Files.delete(record);
        assertTrue(load("n\n", new StringWriter()).isEmpty());
    }

    @Test
    void unreadableStoreNeverAutomaticallyGrantsTrust() throws Exception {
        simpleConfig("exit 0");
        Path brokenStore = root.resolve("broken-store");
        Files.writeString(brokenStore, "not a directory");
        StringWriter output = new StringWriter();
        HookTrustStore store = new HookTrustStore(brokenStore);
        assertTrue(HookTrust.load(project(), input("n\n"), new PrintWriter(output), store).isEmpty());
        assertTrue(output.toString().contains("Hook 信任确认>"));
    }

    @Test
    void storageFailureOnlyGrantsSessionTrustAndReportsIt() throws Exception {
        simpleConfig("exit 0");
        Path brokenStore = root.resolve("broken-store");
        Files.writeString(brokenStore, "not a directory");
        StringWriter output = new StringWriter();
        assertEquals(1, HookTrust.load(project(), input("y\n"), new PrintWriter(output),
                new HookTrustStore(brokenStore)).size());
        assertTrue(output.toString().contains("仅本次启用"));
        assertFalse(output.toString().contains("已保存授权"));
    }

    @Test
    void changesDuringConfirmationAreNeverApproved() throws Exception {
        simpleConfig(markerCommand());
        BufferedReader input = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() throws IOException {
                simpleConfig("exit 0");
                return "y";
            }
        };
        StringWriter output = new StringWriter();
        assertTrue(HookTrust.load(project(), input, new PrintWriter(output), store()).isEmpty());
        assertTrue(output.toString().contains("确认期间"));
        assertFalse(Files.exists(project().resolve("executed.txt")));
        assertFalse(Files.exists(root.resolve("user")));
    }

    @Test
    void midSessionChangeBlocksBeforeAndWarnsAfterWithoutExecutingCommands() throws Exception {
        String marker = markerCommand();
        config(Map.of("PreToolUse", List.of(group(marker)), "Stop", List.of(group(marker))));
        StringWriter output = new StringWriter();
        List<AgentHook> callbacks = load("once\n", output);
        String original = Files.readString(project().resolve(HookConfig.DEFAULT_PATH));
        Files.writeString(project().resolve(HookConfig.DEFAULT_PATH), original + "\n");
        List<String> warnings = new ArrayList<>();
        AgentHooks hooks = new AgentHooks(callbacks, warnings::add);
        assertNotNull(hooks.preToolUse(new ToolCall("x", "shell", "{}")));
        hooks.stop(List.of(new ChatMessage("assistant", "原回复")));
        assertFalse(Files.exists(project().resolve("executed.txt")));
        assertEquals(1, warnings.size());
        // 本次会话已失效，恢复旧内容也不能再次执行。
        Files.writeString(project().resolve(HookConfig.DEFAULT_PATH), original);
        assertNotNull(hooks.preToolUse(new ToolCall("x", "shell", "{}")));
    }

    @Test
    void midSessionScriptChangeInvalidatesApprovedCallbacks() throws Exception {
        Path script = script("test.py", "print('A')");
        simpleConfig("python test.py");
        List<AgentHook> callbacks = load("once\n", new StringWriter());
        Files.writeString(script, "print('B')");
        assertThrows(IOException.class, () -> callbacks.get(0).userPromptSubmit("hello", List.of()));
    }

    @Test
    void denialLeavesJavaHooksAndConversationAvailableAndAnswerOutOfHistory() throws Exception {
        simpleConfig(markerCommand());
        BufferedReader input = input("n\n你好\nexit\n");
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        AtomicInteger javaCalls = new AtomicInteger();
        AgentHook agentHook = new AgentHook() {
            public String userPromptSubmit(String prompt, List<ChatMessage> history) {
                javaCalls.incrementAndGet();
                assertEquals("你好", prompt);
                return null;
            }
        };
        AtomicInteger modelCalls = new AtomicInteger();
        HooksMain.createLoop(agentConfig(), (messages, tools) -> {
            modelCalls.incrementAndGet();
            assertEquals(List.of("你好"), messages.stream().filter(m -> "user".equals(m.role()))
                    .map(ChatMessage::content).toList());
            return new ChatMessage("assistant", "你好");
        }, input, output, project(), List.of(agentHook), store()).run(input, output);
        assertEquals(1, javaCalls.get());
        assertEquals(1, modelCalls.get());
        assertTrue(buffer.toString().contains("钩子 UserPromptSubmit>"));
        assertFalse(Files.exists(project().resolve("executed.txt")));
    }

    @Test
    void hookTrustDoesNotAuthorizeToolWriteAndConfirmationNeverEntersModelHistory() throws Exception {
        config(Map.of("PreToolUse", List.of(group("exit 0"))));
        BufferedReader input = input("once\n写文件\nn\nexit\n");
        StringWriter buffer = new StringWriter();
        AtomicInteger modelCalls = new AtomicInteger();
        PrintWriter output = new PrintWriter(buffer);
        HooksMain.createLoop(agentConfig(), (messages, tools) -> {
            assertEquals(List.of("写文件"), messages.stream().filter(m -> "user".equals(m.role()))
                    .map(ChatMessage::content).toList());
            return modelCalls.incrementAndGet() == 1
                    ? ChatMessage.assistant(null, List.of(new ToolCall("x", "write_file",
                            "{\"path\":\"no.txt\",\"content\":\"text\"}")))
                    : new ChatMessage("assistant", "没有写入");
        }, input, output, project(), List.of(), store()).run(input, output);
        assertEquals(2, modelCalls.get());
        assertTrue(buffer.toString().contains("Hook 信任确认>"));
        assertTrue(buffer.toString().contains("权限确认>"));
        assertTrue(buffer.toString().contains("权限拒绝"));
        assertFalse(Files.exists(project().resolve("tools/no.txt")));
    }

    private AgentConfig agentConfig() {
        return new AgentConfig("http://localhost", "unused", "fake", project().resolve("tools"),
                5, Duration.ofSeconds(5), 12000);
    }

    private Path project() {
        return root.resolve("project");
    }

    private HookTrustStore store() {
        return new HookTrustStore(root.resolve("user/hook-trust"));
    }

    private List<AgentHook> load(String answer, StringWriter buffer) throws IOException {
        return HookTrust.load(project(), input(answer), new PrintWriter(buffer), store());
    }

    private BufferedReader input(String text) {
        return new BufferedReader(new StringReader(text));
    }

    private void simpleConfig(String command) throws IOException {
        config(Map.of("UserPromptSubmit", List.of(group(command))));
    }

    private Map<String, Object> group(String command) {
        return Map.of("hooks", List.of(Map.of("type", "command", "command", command)));
    }

    private void config(Map<String, Object> events) throws IOException {
        Files.createDirectories(project().resolve(".agents"));
        Files.writeString(project().resolve(HookConfig.DEFAULT_PATH), JSON.toJSONString(Map.of("hooks", events)));
    }

    private Path script(String relative, String text) throws IOException {
        Path file = project().resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private String markerCommand() {
        return windows ? "[IO.File]::WriteAllText((Join-Path (Get-Location) 'executed.txt'), 'ok')"
                : "printf ok > executed.txt";
    }
}
