package dev.huagent.prompt;

import java.io.PrintWriter;

/**
 * 在终端上标出用户输入位置的方式。ChatLoop 等待输入前后调用，TeamConsole 在确认问答打断提示符后重画；
 * 只影响终端显示，读到的输入、消息历史与请求内容都不变。调用方负责加锁与 flush。
 * plain 是教程各入口的 "你> "；hu 交互会话使用 FramedPrompt。换样式只需实现这个接口并在 HuMain 装配。
 */
public interface PromptStyle {
    /** 等待输入前调用：画出提示符，光标停在用户输入的位置。 */
    void begin(PrintWriter output);

    /**
     * 读到一行之后、处理它之前调用。line 为 null 表示输入结束；为程序事件的哨兵行（TurnEvents.isWake）时
     * 用户没有回车，光标仍停在提示符后面。默认什么都不做。
     */
    default void end(PrintWriter output, String line) {
    }

    /**
     * ChatLoop 每次等待输入前、begin 之前调用：usedTokens 是主对话最近一次模型请求之后占用的上下文 token 数；
     * 会话刚开始、/model 切换模型后、/clear 或 /compact 替换历史后、接口不返回 usage 时为 -1（未知）。默认什么都不做。
     */
    default void context(int usedTokens) {
    }

    /** ChatLoop 在每次等待输入前同步当前会话模型；默认什么都不做。 */
    default void model(String model) {
    }

    /** 教程入口与测试使用的原样式。 */
    static PromptStyle plain() {
        return output -> output.print("你> ");
    }
}
