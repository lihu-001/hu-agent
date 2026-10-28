package dev.huagent.permission;

import dev.huagent.tool.ToolCall;
import dev.huagent.tool.file.WorkspaceFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** 自动审批模式：ASK 自动批准且不读确认输入，DENY 不变；关闭后恢复逐次确认。 */
class PermissionGateAutoApproveTest {
    private static final ToolCall WRITE = new ToolCall("call_1", "write_file",
            "{\"path\":\"a.txt\",\"content\":\"内容\"}");

    @Test
    void approvesConfirmationsWithoutReadingInput(@TempDir Path root) throws IOException {
        StringWriter shown = new StringWriter();
        PermissionGate gate = gate(root, untouchedInput(), shown, () -> false, new AutoApprove(true));

        assertNull(gate.denialReason(WRITE));
        assertNull(gate.denialReason(new ToolCall("call_2", "shell", "{\"command\":\"mvn test\"}")));

        String n = System.lineSeparator();
        assertEquals("【自动批准】write_file：{\"path\":\"a.txt\",\"content\":\"内容\"}" + n
                + "【自动批准】shell：mvn test" + n, shown.toString());
    }

    @Test
    void hardDenialsAndPathChecksStillApply(@TempDir Path root) throws IOException {
        StringWriter shown = new StringWriter();
        PermissionGate gate = gate(root, untouchedInput(), shown, () -> false, new AutoApprove(true));

        assertTrue(gate.denialReason(new ToolCall("c", "shell", "{\"command\":\"sudo ls\"}")).contains("硬拒绝"));
        assertNotNull(gate.denialReason(new ToolCall("c", "write_file",
                "{\"path\":\"../outside.txt\",\"content\":\"x\"}")), "工作目录之外的路径仍被拒绝");
        assertEquals("没有为该工具定义权限规则。", gate.denialReason(new ToolCall("c", "unknown_tool", "{}")));
        assertEquals("", shown.toString(), "拒绝的调用不显示自动批准记录");
    }

    @Test
    void approvesDuringSyntheticTurns(@TempDir Path root) throws IOException {
        PermissionGate gate = gate(root, untouchedInput(), new StringWriter(), () -> true, new AutoApprove(true));

        assertNull(gate.denialReason(WRITE), "自动审批时程序事件发起的轮次同样放行");
    }

    @Test
    void switchingOffRestoresConfirmation(@TempDir Path root) throws IOException {
        AutoApprove autoApprove = new AutoApprove(true);
        PermissionGate gate = gate(root, new BufferedReader(new StringReader("n\n")), new StringWriter(),
                () -> false, autoApprove);
        assertNull(gate.denialReason(WRITE));

        autoApprove.set(false);

        assertEquals("用户未批准本次调用。", gate.denialReason(WRITE));
    }

    @Test
    void fullStyleListsCallAndSaysItWasApproved(@TempDir Path root) throws IOException {
        StringWriter shown = new StringWriter();
        PermissionGate gate = new PermissionGate(new PermissionPolicy(new WorkspaceFiles(root)), untouchedInput(),
                new PrintWriter(shown, true), () -> false, ConfirmPrompt.full(), new AutoApprove(true));

        assertNull(gate.denialReason(WRITE));

        assertTrue(shown.toString().startsWith("权限确认> 将写入或修改文件"), shown.toString());
        assertTrue(shown.toString().contains("工具> write_file [call_1]"), shown.toString());
        assertTrue(shown.toString().endsWith("自动审批模式，已自动批准。" + System.lineSeparator()), shown.toString());
    }

    private static PermissionGate gate(Path root, BufferedReader input, StringWriter shown,
                                       BooleanSupplier asyncTurn, AutoApprove autoApprove) throws IOException {
        return new PermissionGate(new PermissionPolicy(new WorkspaceFiles(root)), input,
                new PrintWriter(shown, true), asyncTurn, ConfirmPrompt.compact(false), autoApprove);
    }

    /** 读取即失败：自动审批模式不应消费确认输入。 */
    private static BufferedReader untouchedInput() {
        return new BufferedReader(Reader.nullReader()) {
            @Override
            public String readLine() {
                throw new AssertionError("自动审批模式不应读取确认输入");
            }
        };
    }
}
