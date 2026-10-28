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
