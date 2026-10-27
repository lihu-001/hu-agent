package dev.huagent.command;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 命令的结果，三种之一：
 * PRINT 只把 text 打印到终端（本地命令）；SUBMIT 把 text 作为本轮输入交给模型（提示词命令），
 * 经过 UserPromptSubmit 放行后才调用 onAccepted，被拒绝时在提示后附 rejectedNote；EXIT 结束会话。
 */
public record CommandResult(Kind kind, String text, Supplier<String> onAccepted, String rejectedNote) {
    public enum Kind {
        PRINT, SUBMIT, EXIT
    }

    public CommandResult {
        Objects.requireNonNull(kind);
        if (kind != Kind.EXIT) {
            Objects.requireNonNull(text);
        }
        rejectedNote = rejectedNote == null ? "" : rejectedNote;
    }

    public static CommandResult print(String text) {
        return new CommandResult(Kind.PRINT, text, null, "");
    }

    /** onAccepted 返回放行后打印的一行说明（可以为 null）；rejectedNote 是被 Hook 拒绝时追加的说明。 */
    public static CommandResult submit(String prompt, Supplier<String> onAccepted, String rejectedNote) {
        return new CommandResult(Kind.SUBMIT, prompt, onAccepted, rejectedNote);
    }

    public static CommandResult exit() {
        return new CommandResult(Kind.EXIT, null, null, "");
    }
}
