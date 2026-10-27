package dev.huagent.harness;

/**
 * S15：程序事件唤醒循环时写入输入流的哨兵行。它不是用户的输入：
 * ChatLoop 识别后不记录用户消息、不触发 UserPromptSubmit，只把待送达的程序事件交给模型。
 * 行内容刻意用标签形式，用户手动输入完全相同的文本时也会按事件处理，不会有副作用。
 */
public final class TurnEvents {
    public static final String WAKE_LINE = "<system-events>";

    private TurnEvents() {
    }

    public static boolean isWake(String line) {
        return WAKE_LINE.equals(line.strip());
    }
}
