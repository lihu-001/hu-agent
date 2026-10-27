package dev.huagent.permission;

import dev.huagent.tool.ToolCall;
import dev.huagent.tool.file.WorkspaceFiles;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static dev.huagent.permission.PermissionPolicy.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class PermissionPolicyTest {
    @TempDir
    Path root;
    private PermissionPolicy policy;

    @BeforeEach
    void setUp() throws IOException {
        policy = new PermissionPolicy(new WorkspaceFiles(root));
    }

    @Test
    void readsAndGlobAreAllowedWhileWritesAndEditsAsk() {
        assertEquals(ALLOW, check("read_file", Map.of("path", "note.txt")).action());
        assertEquals(ALLOW, check("glob", Map.of("pattern", "**/*.txt")).action());
        assertEquals(ALLOW, check("todo_write", Map.of("todos", java.util.List.of())).action());
        assertEquals(ALLOW, check("task", Map.of("description", "a", "prompt", "b")).action());
        assertEquals(ALLOW, check("load_skill", Map.of("name", "code-review")).action());
        assertEquals(ALLOW, check("compact", Map.of()).action());
        assertEquals(ALLOW, check("memory_read", Map.of("name", "a")).action());
        assertEquals(ASK, check("memory_write", Map.of("name", "a")).action());
        assertEquals(ASK, check("memory_delete", Map.of("name", "a")).action());
        for (String tool : java.util.List.of("task_create", "task_update", "task_list", "task_get",
                "task_claim", "task_complete")) {
            assertEquals(ALLOW, check(tool, Map.of()).action(), tool);
        }
        assertEquals(ASK, check("background_run", Map.of("command", "mvn test")).action());
        assertEquals(DENY, check("background_run", Map.of("command", "sudo id")).action());
        assertEquals(DENY, check("background_run", Map.of()).action());
        assertEquals(ALLOW, check("background_check", Map.of()).action());
        assertEquals(ASK, check("schedule_create", Map.of("name", "n", "cron", "* * * * *",
                "command", "mvn test")).action());
        assertEquals(DENY, check("schedule_create", Map.of("name", "n", "cron", "* * * * *",
                "command", "sudo id")).action());
        assertEquals(DENY, check("schedule_create", Map.of("name", "n", "cron", "* * * * *")).action());
        assertEquals(ALLOW, check("schedule_list", Map.of()).action());
        assertEquals(ALLOW, check("schedule_get", Map.of("id", "schedule_0a1b2c3d")).action());
        assertEquals(ALLOW, check("schedule_pause", Map.of("id", "schedule_0a1b2c3d")).action());
        assertEquals(ASK, check("schedule_resume", Map.of("id", "schedule_0a1b2c3d")).action());
        assertEquals(ASK, check("schedule_delete", Map.of("id", "schedule_0a1b2c3d")).action());
        // S13：创建队友逐次确认；消息、查看与关闭只读写收件箱。
        assertEquals(ASK, check("spawn_teammate", Map.of("name", "alice", "role", "r", "prompt", "p")).action());
        assertEquals(ALLOW, check("send_message", Map.of("to", "lead", "content", "hi")).action());
        assertEquals(ALLOW, check("list_teammates", Map.of()).action());
        assertEquals(ALLOW, check("shutdown_teammate", Map.of("name", "alice")).action());
        // S16：工作流工具只读写 .workflows；步骤经子代理执行，其工具调用另行检查。
        for (String tool : java.util.List.of("workflow_create", "workflow_run", "workflow_list",
                "workflow_get", "workflow_cancel")) {
            assertEquals(ALLOW, check(tool, Map.of("id", "workflow_0a1b2c3d", "name", "n",
                    "steps", java.util.List.of("一步"))).action(), tool);
        }
        assertEquals(ASK, check("write_file", Map.of("path", "new/note.txt", "content", "x")).action());
        assertEquals(ASK, check("edit_file", Map.of("path", "note.txt", "old_text", "x", "new_text", "y")).action());
        assertEquals(ASK, check("write_file", Map.of("path", root.resolve("inside.txt").toString())).action());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Get-ChildItem -Name", "Get-Content example.txt", "echo hello",
            "git status", "echo first; echo second", "Remove-Item demo.txt", "rm demo.txt"})
    void everyShellCommandWithoutAHardDenyMatchAsks(String command) {
        assertEquals(ASK, check("shell", Map.of("command", command)).action());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sudo id", "shutdown /s", "SHUTDOWN.EXE /s", "reboot", "mkfs.ext4 /dev/test",
            "dd if=/dev/zero", "echo data > /dev/sda", "rm -rf /", "rm -fr / ; echo x",
            "Stop-Computer", "Restart-Computer", "Format-Volume", "Clear-Disk", "Remove-Partition"})
    void hardDenyCommandsAreClassifiedWithoutExecution(String command) {
        assertEquals(DENY, check("shell", Map.of("command", command)).action());
    }

    @Test
    void pathsOutsideWorkspaceAreDeniedForAllFileOperations() {
        for (String tool : new String[]{"read_file", "write_file", "edit_file"}) {
            for (String path : new String[]{"../outside.txt", root.resolveSibling("other/file.txt").toString()}) {
                PermissionPolicy.Decision decision = check(tool, Map.of("path", path));
                assertEquals(DENY, decision.action());
                assertTrue(decision.reason().contains("工作目录"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "bad json", "{}", "{\"path\":12}", "{\"path\":\" \"}"})
    void invalidPermissionArgumentsAreDenied(String arguments) {
        assertEquals(DENY, policy.evaluate(new ToolCall("id", "write_file", arguments)).action());
    }

    @Test
    void invalidCommandAndUnknownToolAreDenied() {
        assertEquals(DENY, check("shell", Map.of("command", 12)).action());
        assertEquals(DENY, check("shell", Map.of("command", " ")).action());
        assertEquals(DENY, check("unregistered", Map.of()).action());
    }

    @Test
    void mcpToolsAskUnlessAllowListed() {
        // S14：外部工具默认逐次确认；只有 mcp.json allow 中的全名直接允许，无效 JSON 仍拒绝。
        assertEquals(ASK, check("mcp__docs__search", Map.of("q", "x")).action());
        PermissionPolicy allowing = new PermissionPolicy(policyFiles(), java.util.Set.of("mcp__docs__search"));
        assertEquals(ALLOW, allowing.evaluate(new ToolCall("id", "mcp__docs__search", "{}")).action());
        assertEquals(ASK, allowing.evaluate(new ToolCall("id", "mcp__docs__write", "{}")).action());
        assertEquals(DENY, allowing.evaluate(new ToolCall("id", "mcp__docs__search", "not json")).action());
        assertEquals(DENY, allowing.evaluate(new ToolCall("id", "mcpdocs", "{}")).action());
    }

    private WorkspaceFiles policyFiles() {
        try {
            return new WorkspaceFiles(root);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private PermissionPolicy.Decision check(String tool, Map<String, Object> args) {
        return policy.evaluate(new ToolCall("id", tool, JSON.toJSONString(args)));
    }
}
