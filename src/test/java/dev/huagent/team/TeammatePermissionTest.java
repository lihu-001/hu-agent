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
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    @Test
    void answeringAStopsAskingTheTeammateAndTheLeadShareTheScope(@TempDir Path root) throws Exception {
        StringWriter shown = new StringWriter();
        PipedWriter keyboard = new PipedWriter();
        TeamConsole console = new TeamConsole(new BufferedReader(new PipedReader(keyboard)), new PrintWriter(shown),
                PromptStyle.plain(), ConfirmPrompt.compact(false));
        AutoApprove autoApprove = new AutoApprove(false);
        TeammatePermission permission = new TeammatePermission(new PermissionPolicy(new WorkspaceFiles(root)),
                console, "alice", autoApprove);
        // 队友的确认由主线程在等待 Lead 输入时询问。
        BufferedReader prompts = console.prompts(() -> null);
        CompletableFuture<String> lead = CompletableFuture.supplyAsync(() -> {
            try {
                return prompts.readLine();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
            try {
                return permission.denialReason(call);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        awaitText(shown, "允许队友 alice 本次调用？[y/N，a=本次会话不再询问这条命令] ");
        keyboard.write("a\n");
        keyboard.flush();
        assertNull(first.get(5, TimeUnit.SECONDS));

        // 同一条命令不再排队询问，只显示一行记录；Lead 的放行范围队友同样适用。
        assertNull(permission.denialReason(new ToolCall("c2", "shell", "{\"command\":\"echo hi\"}")));
        awaitText(shown, "【本会话允许】[alice] shell：echo hi" + System.lineSeparator());
        autoApprove.allowInSession(new ToolCall("c3", "write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}"));
        assertNull(permission.denialReason(new ToolCall("c4", "edit_file",
                "{\"path\":\"a.txt\",\"old_text\":\"x\",\"new_text\":\"y\"}")));
        keyboard.close();
        lead.get(5, TimeUnit.SECONDS);
    }

    private static void awaitText(StringWriter buffer, String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!buffer.toString().contains(text)) {
            if (System.nanoTime() > deadline) {
                fail("没有出现：" + text + "\n" + buffer);
            }
            Thread.sleep(10);
        }
    }
}
