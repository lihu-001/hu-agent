package dev.huagent.permission;

import dev.huagent.tool.ToolCall;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmPromptTest {
    @Test
    void fullKeepsReasonIdAndArgumentsOnSeparateLines() {
        String shown = ask(ConfirmPrompt.full(), new ToolCall("call_1", "shell", "{\"command\":\"echo hi\"}"));
        String n = System.lineSeparator();
        assertEquals("权限确认> 需要确认" + n + "工具> shell [call_1]" + n + "参数> {\"command\":\"echo hi\"}" + n
                + "仅允许本次调用？[y/N] ", shown);
    }

    @Test
    void compactShowsSingleStringArgumentDirectlyOnOneLine() {
        String shown = ask(ConfirmPrompt.compact(false),
                new ToolCall("call_1", "shell", "{\"command\":\"Get-Date -Format 'yyyy-MM-dd dddd'\"}"));
        assertEquals("【权限确认】shell：Get-Date -Format 'yyyy-MM-dd dddd'  仅允许本次调用？[y/N] ", shown);
    }

    @Test
    void compactShowsOtherArgumentsAsOneLineJson() {
        assertEquals("{\"path\":\"a.txt\",\"content\":\"第一行\\n第二行\"}",
                ConfirmPrompt.detail("{\n  \"path\": \"a.txt\",\n  \"content\": \"第一行\\n第二行\"\n}"));
        // 单个字段含换行或控制字符时不直接显示，转义后仍是一行，也不会被终端执行。
        assertEquals("{\"command\":\"cd src\\nmvn test\"}", ConfirmPrompt.detail("{\"command\":\"cd src\\nmvn test\"}"));
        assertFalse(ConfirmPrompt.detail("{\"command\":\"echo \\u001b[2J\"}").contains("\u001b"));
        // 双向覆盖等格式字符 JSON 不转义，同样转成 \\uXXXX，显示的内容不会与实际参数顺序不同。
        assertEquals("{\"command\":\"echo \\u202eabc\"}", ConfirmPrompt.detail("{\"command\":\"echo \u202Eabc\"}"));
        assertEquals("{\"limit\":3}", ConfirmPrompt.detail("{\"limit\":3}"));
        assertEquals("\"not json\"", ConfirmPrompt.detail("not json"));
    }

    @Test
    void compactPutsQuestionOnNextLineWhenArgumentsAreLong() {
        String content = "x".repeat(ConfirmPrompt.INLINE_COLUMNS);
        String shown = ask(ConfirmPrompt.compact(false),
                new ToolCall("c", "write_file", "{\"path\":\"a.txt\",\"content\":\"" + content + "\"}"));
        assertEquals("【权限确认】write_file：{\"path\":\"a.txt\",\"content\":\"" + content + "\"}"
                + System.lineSeparator() + "  仅允许本次调用？[y/N] ", shown);
    }

    @Test
    void colorMarksLabelAndQuestion() {
        String shown = ask(ConfirmPrompt.compact(true), new ToolCall("c", "shell", "{\"command\":\"ls\"}"));
        assertEquals("\u001b[1;33m【权限确认】\u001b[0m\u001b[1mshell\u001b[0m：ls  \u001b[33m仅允许本次调用？[y/N]\u001b[0m ",
                shown);
    }

    @Test
    void approvedIsOneFinishedLineAndNamesTheTeammate() {
        ToolCall call = new ToolCall("c", "shell", "{\"command\":\"ls\"}");
        String n = System.lineSeparator();
        assertEquals("【自动批准】shell：ls" + n, approved(ConfirmPrompt.compact(false), call, null));
        assertEquals("【自动批准】[alice] shell：ls" + n, approved(ConfirmPrompt.compact(false), call, "alice"));
        assertEquals("\u001b[1;33m【自动批准】\u001b[0m\u001b[1mshell\u001b[0m：ls" + n,
                approved(ConfirmPrompt.compact(true), call, null));
        assertEquals("权限确认> [alice] 需要确认" + n + "工具> shell [c]" + n + "参数> {\"command\":\"ls\"}" + n
                + "自动审批模式，已自动批准。" + n, approved(ConfirmPrompt.full(), call, "alice"));
    }

    @Test
    void compactShowsEditAsNumberedDiffWithQuestionOnItsOwnLine() {
        ToolCall call = new ToolCall("e", "edit_file", "{\"path\":\"A.java\",\"old_text\":\"b\",\"new_text\":\"B\"}");
        ChangePreview preview = ChangePreview.edit("A.java", "a\n\tb\nc\n", "b", "B\u001b[2J", false);
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        ConfirmPrompt.compact(false).ask(output, "需要确认", call, preview, "仅允许本次调用？");
        output.flush();
        String n = System.lineSeparator();
        // 制表符展开、控制字符转义，问题单独一行（它会成为行编辑器的提示符）。
        assertEquals("【权限确认】edit_file：A.java（+1 −1）" + n
                + "      1   a" + n
                + "      2 -     b" + n
                + "      2 +     B\\u001b[2J" + n
                + "      3   c" + n
                + "  仅允许本次调用？[y/N] ", buffer.toString());
    }

    @Test
    void colorsMarkAdditionsAndRemovalsAndAutoApprovalShowsThePreviewToo() {
        ToolCall call = new ToolCall("w", "write_file", "{\"path\":\"n.txt\",\"content\":\"x\"}");
        ChangePreview preview = ChangePreview.write("n.txt", "old", "new");
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        ConfirmPrompt.compact(true).approved(output, "需要确认", call, preview, "alice");
        output.flush();
        String shown = buffer.toString();
        assertTrue(shown.startsWith("\u001b[1;33m【自动批准】\u001b[0m[alice] \u001b[1mwrite_file\u001b[0m："
                + "n.txt（覆盖已有文件，+1 −1）"), shown);
        assertTrue(shown.contains("\u001b[31m      1 - old\u001b[0m"), shown);
        assertTrue(shown.contains("\u001b[32m      1 + new\u001b[0m"), shown);
    }

    @Test
    void multiLineCommandIsShownLineByLine() {
        ToolCall call = new ToolCall("s", "shell", "{\"command\":\"cd src\\nmvn test\"}");
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        ConfirmPrompt.compact(false).ask(output, "需要确认", call, ChangePreview.command("cd src\nmvn test"),
                "仅允许本次调用？");
        output.flush();
        String n = System.lineSeparator();
        assertEquals("【权限确认】shell：多行命令，共 2 行" + n + "    cd src" + n + "    mvn test" + n
                + "  仅允许本次调用？[y/N] ", buffer.toString());
    }

    private static String approved(ConfirmPrompt confirm, ToolCall call, String member) {
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        confirm.approved(output, "需要确认", call, member);
        output.flush();
        return buffer.toString();
    }

    private static String ask(ConfirmPrompt confirm, ToolCall call) {
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        confirm.ask(output, "需要确认", call, "仅允许本次调用？");
        output.flush();
        return buffer.toString();
    }
}
