package dev.huagent.team;

import dev.huagent.permission.ConfirmPrompt;
import dev.huagent.permission.PermissionPolicy;
import dev.huagent.permission.AutoApprove;
import dev.huagent.prompt.PromptStyle;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.file.WorkspaceFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TeammatePermissionTest {
    private final ToolCall call = new ToolCall("c1", "shell", "{\"command\":\"echo hi\"}");

    @Test
    void autoApproveSkipsTeammateConfirmationQueue(@TempDir Path root) throws Exception {
        StringWriter shown = new StringWriter();
        // 终端没有输入：若仍排队等用户回答，会因输入结束被拒绝。
        TeamConsole console = new TeamConsole(new BufferedReader(new StringReader("")), new PrintWriter(shown),
                PromptStyle.plain(), ConfirmPrompt.compact(false));
        AutoApprove autoApprove = new AutoApprove(true);
        TeammatePermission permission = new TeammatePermission(new PermissionPolicy(new WorkspaceFiles(root)),
                console, "alice", autoApprove);

        assertNull(permission.denialReason(call));
        assertEquals("【自动批准】[alice] shell：echo hi" + System.lineSeparator(), shown.toString());
        assertTrue(permission.denialReason(new ToolCall("c2", "shell", "{\"command\":\"sudo ls\"}"))
                .contains("硬拒绝"), "硬拒绝不受自动审批影响");

        // 关闭后恢复逐次确认：控制台已关闭时确认请求直接拒绝，不会阻塞。
        autoApprove.set(false);
        console.close();
        assertEquals("用户未批准本次调用。", permission.denialReason(call));
    }
}
