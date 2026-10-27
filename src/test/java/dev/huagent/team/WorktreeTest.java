package dev.huagent.team;

import dev.huagent.app.TeamMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.hook.HookTrustStore;
import dev.huagent.model.ChatMessage;
import dev.huagent.model.ModelClient;
import dev.huagent.task.TaskRecord;
import dev.huagent.task.TaskStatus;
import dev.huagent.task.TaskStore;
import dev.huagent.todo.TodoList;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 每个队友一个 git worktree；在临时目录中建真实仓库，本机没有 git 时跳过。 */
class WorktreeTest {
    @TempDir
    Path root;
    private Path repo;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "本机没有 git");
        repo = Files.createDirectories(root.resolve("repo"));
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "test");
        git(repo, "config", "commit.gpgsign", "false");
        git(repo, "config", "core.autocrlf", "false");
        Files.writeString(repo.resolve("readme.txt"), "hello\n", StandardCharsets.UTF_8);
        git(repo, "add", "readme.txt");
        git(repo, "commit", "-m", "init");
    }

    @Test
    void preparesWorktreePerTeammateAndReusesIt() throws Exception {
        WorktreeManager manager = new WorktreeManager(repo);
        assertTrue(manager.isRepositoryRoot());
        WorktreeManager.Worktree alice = manager.prepare("alice");
        assertEquals(repo.resolve(".worktrees").resolve("alice"), alice.path());
        assertEquals("wt/alice", alice.branch());
        assertEquals("main", alice.base());
        // 系统级 core.attributes / autocrlf 可能改换行，只比较内容。
        assertEquals("hello", Files.readString(alice.path().resolve("readme.txt")).strip());
        String list = git(repo, "worktree", "list", "--porcelain");
        assertTrue(list.contains("branch refs/heads/wt/alice"), list);
        assertEquals("", git(repo, "status", "--porcelain").strip(), ".worktrees/ 写进 info/exclude，主目录保持干净");

        // 同一会话或下次会话的同名队友接着用上次的副本。
        Files.writeString(alice.path().resolve("draft.txt"), "x");
        assertEquals(alice.path(), manager.prepare("alice").path());
        assertTrue(Files.exists(alice.path().resolve("draft.txt")));
        assertNotEquals(alice.path(), manager.prepare("bob").path());
    }

    @Test
    void refusesWhenWorkDirectoryIsNotRepositoryRootOrNamesCollide() throws Exception {
        Path plain = Files.createDirectories(root.resolve("plain"));
        IllegalArgumentException notRepo = assertThrows(IllegalArgumentException.class,
                () -> new WorktreeManager(plain).prepare("alice"));
        assertTrue(notRepo.getMessage().contains("不是 git 仓库"), notRepo.getMessage());

        Path sub = Files.createDirectories(repo.resolve("sub"));
        IllegalArgumentException subdirectory = assertThrows(IllegalArgumentException.class,
                () -> new WorktreeManager(sub).prepare("alice"));
        assertTrue(subdirectory.getMessage().contains("子目录"), subdirectory.getMessage());
        assertFalse(new WorktreeManager(sub).isRepositoryRoot());

        git(repo, "branch", "wt/carol");
        assertThrows(IllegalArgumentException.class, () -> new WorktreeManager(repo).prepare("carol"),
                "分支已存在但没有 worktree");
        Files.createDirectories(repo.resolve(".worktrees").resolve("dave"));
        assertThrows(IllegalArgumentException.class, () -> new WorktreeManager(repo).prepare("dave"),
                "目录已存在但不是 worktree");
    }

    @Test
    void spawnWithoutWorktreeSupportOrOutsideRepositoryLeavesNothingBehind() throws Exception {
        Path plain = Files.createDirectories(root.resolve("plain"));
        TaskStore tasks = new TaskStore(plain.resolve(TaskStore.DIRECTORY_NAME));
        TaskRecord task = tasks.create("t", null);
        MessageBus bus = new MessageBus(plain.resolve(MessageBus.DIRECTORY_NAME), to -> { });
        TeamManager team = new TeamManager(bus, tasks, (name, role, worktree, manager, out) -> {
            throw new AssertionError("前置条件不满足时不应组装队友");
        }, new PrintWriter(new StringWriter()), Duration.ofSeconds(5), new WorktreeManager(plain));
        assertThrows(IllegalArgumentException.class, () -> team.spawn("alice", "r", "p", task.id(), true));
        assertTrue(team.names().isEmpty());
        assertEquals(TaskStatus.PENDING, tasks.load(task.id()).status(), "失败时不留下认领");

        TeamManager without = new TeamManager(bus, tasks, (name, role, worktree, manager, out) -> {
            throw new AssertionError();
        }, new PrintWriter(new StringWriter()), Duration.ofSeconds(5));
        assertThrows(IllegalArgumentException.class, () -> without.spawn("alice", "r", "p", null, true));
    }

    @Test
    void worktreeTeammateWritesOnlyInItsCopyRunsShellThereAndSharesTasks() throws Exception {
        TaskStore tasks = new TaskStore(repo.resolve(TaskStore.DIRECTORY_NAME));
        TaskRecord task = tasks.create("写 a.txt", null);
        List<List<ChatMessage>> aliceRequests = new CopyOnWriteArrayList<>();
        CompletableFuture<String> result = new CompletableFuture<>();
        ModelClient client = (messages, tools) -> {
            ChatMessage last = messages.get(messages.size() - 1);
            if (messages.get(0).content().contains("你的名字是 alice")) {
                aliceRequests.add(messages);
                int toolResults = (int) messages.stream().filter(m -> "tool".equals(m.role())).count();
                return switch (toolResults) {
                    case 0 -> ChatMessage.assistant(null, List.of(call("w1", "write_file",
                            Map.of("path", "a.txt", "content", "from alice"))));
                    case 1 -> ChatMessage.assistant(null, List.of(call("s1", "shell",
                            Map.of("command", "git rev-parse --show-toplevel"))));
                    case 2 -> ChatMessage.assistant(null, List.of(call("c1", "task_complete",
                            Map.of("id", task.id()))));
                    default -> new ChatMessage("assistant", "写好了");
                };
            }
            if ("user".equals(last.role()) && last.content().startsWith("<team-events>")) {
                result.complete(last.content());
                return new ChatMessage("assistant", "收到");
            }
            if ("tool".equals(last.role())) {
                return new ChatMessage("assistant", "已安排");
            }
            return ChatMessage.assistant(null, List.of(call("p1", TeamTools.SPAWN, Map.of("name", "alice",
                    "role", "写手", "prompt", "写 a.txt", "task_id", task.id(), "worktree", true))));
        };
        PipedWriter keyboard = new PipedWriter();
        StringWriter buffer = new StringWriter();
        TeamMain.Session session = session(new BufferedReader(new PipedReader(keyboard)), buffer, client);
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            try {
                session.run();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        type(keyboard, "安排 alice");
        awaitCount(buffer, "仅允许本次调用？", 1);
        type(keyboard, "y");
        awaitCount(buffer, "允许队友 alice 本次调用？", 1);
        type(keyboard, "y");
        awaitCount(buffer, "允许队友 alice 本次调用？", 2);
        type(keyboard, "y");
        String events = result.get(20, TimeUnit.SECONDS);
        type(keyboard, "exit");
        running.get(20, TimeUnit.SECONDS);

        Path copy = repo.resolve(".worktrees").resolve("alice");
        assertEquals("from alice", Files.readString(copy.resolve("a.txt")));
        assertFalse(Files.exists(repo.resolve("a.txt")), "主目录不受队友写入影响");
        String shell = aliceRequests.get(aliceRequests.size() - 1).stream()
                .filter(m -> "tool".equals(m.role())).map(ChatMessage::content).toList().get(1);
        assertTrue(shell.replace('\\', '/').contains(copy.toRealPath().toString().replace('\\', '/'))
                || shell.contains(".worktrees/alice"), shell);
        assertEquals(TaskStatus.COMPLETED, tasks.load(task.id()).status(), "任务仍在主目录 .tasks 中共享");
        assertFalse(Files.exists(copy.resolve(TaskStore.DIRECTORY_NAME)));
        assertTrue(events.contains("（工作分支 wt/alice）"), events);
        String system = aliceRequests.get(0).get(0).content();
        assertTrue(system.contains("分支 wt/alice，基于 main"), system);
        assertTrue(buffer.toString().contains("分支 wt/alice，基于 main"), "spawn 结果说明队友位置");
        assertTrue(git(repo, "branch", "--list", "wt/alice").contains("wt/alice"), "会话结束后分支保留");
    }

    @Test
    void globSkipsWorktreeCopiesInMainDirectory() throws Exception {
        new WorktreeManager(repo).prepare("alice");
        var files = new dev.huagent.tool.file.WorkspaceFiles(repo);
        String found = new dev.huagent.tool.file.GlobTool(files).execute("{\"pattern\":\"**/readme.txt\"}");
        assertTrue(found.contains("readme.txt"), found);
        assertFalse(found.contains(".worktrees"), found);
    }

    private TeamMain.Session session(BufferedReader input, StringWriter buffer, ModelClient client)
            throws IOException {
        Path project = Files.createDirectories(root.resolve("project"));
        AgentConfig config = new AgentConfig("http://localhost", "unused", "fake", repo, 8,
                Duration.ofSeconds(20), 12000, 50000, 3, Duration.ofSeconds(30)).withTeamEnabled(true);
        return TeamMain.createSession(config, client, input, new PrintWriter(buffer, true), project, List.of(),
                new HookTrustStore(root.resolve("trust")), new TodoList(), ZoneOffset.UTC,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), false, Duration.ofSeconds(5));
    }

    private static boolean gitAvailable() {
        try {
            return WorktreeManager.run(Path.of("."), "--version").exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String git(Path directory, String... args) throws Exception {
        WorktreeManager.Result result = WorktreeManager.run(directory, args);
        assertEquals(0, result.exitCode(), "git " + String.join(" ", args) + "：" + result.output());
        return result.output();
    }

    private static void type(PipedWriter keyboard, String line) throws IOException {
        keyboard.write(line + "\n");
        keyboard.flush();
    }

    private static void awaitCount(StringWriter buffer, String text, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (buffer.toString().split(java.util.regex.Pattern.quote(text), -1).length - 1 < count) {
            if (System.nanoTime() > deadline) {
                fail("没有出现第 " + count + " 次：" + text + "\n" + buffer);
            }
            Thread.sleep(10);
        }
    }

    private static ToolCall call(String id, String name, Map<String, ?> args) {
        return new ToolCall(id, name, JSON.toJSONString(args));
    }

    @SuppressWarnings("unused")
    private static List<String> names(List<ToolDefinition> definitions) {
        return definitions.stream().map(ToolDefinition::name).toList();
    }
}
