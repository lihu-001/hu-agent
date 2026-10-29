package dev.huagent.permission;

import dev.huagent.tool.ToolCall;
import dev.huagent.tool.file.WorkspaceFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** hu 的手动确认（自动审批关闭）：回答 a 后本次会话同一范围不再询问；硬拒绝与路径检查照常生效。 */
class PermissionGateSessionTest {
    private static final ToolCall TEST = new ToolCall("c1", "shell", "{\"command\":\"mvn test\"}");

    @Test
    void answeringAStopsAskingForTheSameCommand(@TempDir Path root) throws IOException {
        StringWriter shown = new StringWriter();
        BufferedReader input = new BufferedReader(new StringReader("a\nn\n"));
        PermissionGate gate = gate(root, input, shown, () -> false, new AutoApprove(false));

        assertNull(gate.denialReason(TEST));
        assertNull(gate.denialReason(new ToolCall("c2", "shell", "{\"command\":\"mvn test\"}")));
        assertEquals("用户未批准本次调用。",
                gate.denialReason(new ToolCall("c3", "shell", "{\"command\":\"git push\"}")), "别的命令照常询问");

        String n = System.lineSeparator();
        assertEquals("【权限确认】shell：mvn test  仅允许本次调用？[y/N，a=本次会话不再询问这条命令] "
                + "【本会话允许】shell：mvn test" + n
                + "【权限确认】shell：git push  仅允许本次调用？[y/N，a=本次会话不再询问这条命令] ", shown.toString());
        assertNull(input.readLine(), "已放行的调用不读确认输入");
    }

    @Test
    void answeringYAllowsOnlyThisCall(@TempDir Path root) throws IOException {
        PermissionGate gate = gate(root, new BufferedReader(new StringReader("y\nn\n")), new StringWriter(),
                () -> false, new AutoApprove(false));

        assertNull(gate.denialReason(TEST));
        assertEquals("用户未批准本次调用。", gate.denialReason(TEST));
    }

    @Test
    void fileEditsAreAllowedTogetherButPathChecksStillApply(@TempDir Path root) throws IOException {
        StringWriter shown = new StringWriter();
        PermissionGate gate = gate(root, new BufferedReader(new StringReader("a\n")), shown, () -> false,
                new AutoApprove(false));

        assertNull(gate.denialReason(new ToolCall("w", "write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}")));
        assertTrue(shown.toString().contains("[y/N，a=本次会话不再询问文件修改]"), shown.toString());
        assertNull(gate.denialReason(new ToolCall("e", "edit_file",
                "{\"path\":\"a.txt\",\"old_text\":\"x\",\"new_text\":\"y\"}")), "写文件放行后编辑也不再询问");
        assertTrue(shown.toString().contains("【本会话允许】edit_file："), shown.toString());
        assertNotNull(gate.denialReason(new ToolCall("o", "write_file",
                "{\"path\":\"../outside.txt\",\"content\":\"x\"}")), "工作目录之外的路径仍被拒绝");
    }

    @Test
    void hardDenialsAreNeverAllowedForTheSession(@TempDir Path root) throws IOException {
        AutoApprove autoApprove = new AutoApprove(false);
        ToolCall sudo = new ToolCall("s", "shell", "{\"command\":\"sudo ls\"}");
        autoApprove.allowInSession(sudo);
        PermissionGate gate = gate(root, new BufferedReader(new StringReader("")), new StringWriter(), () -> false,
                autoApprove);

        assertTrue(gate.denialReason(sudo).contains("硬拒绝"));
    }

    @Test
    void allowedCallsRunInSyntheticTurnsOthersAreStillDenied(@TempDir Path root) throws IOException {
        AutoApprove autoApprove = new AutoApprove(false);
        autoApprove.allowInSession(TEST);
        PermissionGate gate = gate(root, new BufferedReader(new StringReader("")), new StringWriter(), () -> true,
                autoApprove);

        assertNull(gate.denialReason(TEST));
        assertTrue(gate.denialReason(new ToolCall("c", "shell", "{\"command\":\"git push\"}"))
                .contains("程序事件自动发起"));
    }

    private static PermissionGate gate(Path root, BufferedReader input, StringWriter shown,
                                       BooleanSupplier asyncTurn, AutoApprove autoApprove) throws IOException {
        return new PermissionGate(new PermissionPolicy(new WorkspaceFiles(root)), input,
                new PrintWriter(shown, true), asyncTurn, ConfirmPrompt.compact(false), autoApprove);
    }
}
