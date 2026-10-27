package dev.huagent.permission;

import dev.huagent.tool.ToolCall;
import dev.huagent.tool.file.WorkspaceFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** S15：程序事件发起的哨兵轮次直接拒绝交互确认，且不读取确认输入流。 */
class PermissionGateAsyncTest {
    private static final ToolCall WRITE = new ToolCall("call_1", "write_file",
            "{\"path\":\"a.txt\",\"content\":\"内容\"}");

    @Test
    void deniesInteractiveConfirmationDuringSyntheticTurn(@TempDir Path root) throws IOException {
        WorkspaceFiles files = new WorkspaceFiles(root);
        // 输入流没有任何内容：若实现错误地去读取，会阻塞或得到 null，两种都算失败。
        PermissionGate gate = new PermissionGate(new PermissionPolicy(files),
                new BufferedReader(new StringReader("")), nullOut(), () -> true);

        String reason = gate.denialReason(WRITE);

        assertTrue(reason.contains("程序事件自动发起"));
    }

    @Test
    void stillAsksTheUserOutsideSyntheticTurns(@TempDir Path root) throws IOException {
        WorkspaceFiles files = new WorkspaceFiles(root);
        PermissionGate gate = new PermissionGate(new PermissionPolicy(files),
                new BufferedReader(new StringReader("n\n")), nullOut(), () -> false);

        String reason = gate.denialReason(WRITE);

        assertEquals("用户未批准本次调用。", reason);
    }

    @Test
    void nullSupplierKeepsOldBehavior(@TempDir Path root) throws IOException {
        WorkspaceFiles files = new WorkspaceFiles(root);
        PermissionGate gate = new PermissionGate(new PermissionPolicy(files),
                new BufferedReader(new StringReader("y\n")), nullOut(), null);

        assertNull(gate.denialReason(WRITE));
    }

    private static java.io.PrintWriter nullOut() {
        return new java.io.PrintWriter(new java.io.StringWriter(), true);
    }
}
