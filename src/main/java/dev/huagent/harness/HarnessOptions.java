package dev.huagent.harness;

import dev.huagent.permission.ConfirmPrompt;
import dev.huagent.permission.AutoApprove;
import dev.huagent.prompt.PromptStyle;

import java.io.BufferedReader;

/**
 * S15：集成装配的可选增强。为 null 时（各上一课入口）组装结果与 S14 完全相同；
 * 提供 events 与 retry 时启用事件唤醒、限流退避与响应式压缩。
 * loopInput 是循环专用的输入视图（可被程序事件唤醒）；确认类输入仍使用装配方法的 input。
 * debug 为 false 时（hu 默认）终端不显示模型请求与耗时、内置 Hook 日志、微压缩说明、启动清单、
 * 工具调用与中间回复（见 ToolTrace）；为 true 时与教程入口的完整输出相同。
 * prompt 是 Lead 等待输入时的提示符样式，hu 交互会话用 FramedPrompt，其余为 "你> "。
 * confirm 是权限确认的样式：debug 时为原来的多行（full），否则为一行（compact），hu 在能解释 ANSI 的终端上带颜色。
 * autoApprove 是 Lead、子代理与队友共用的自动审批模式开关（见 AutoApprove）；只有 hu 会打开，其余入口始终关闭。
 */
public record HarnessOptions(EventHub events, ModelRetryPolicy retry, BufferedReader loopInput, boolean debug,
                             PromptStyle prompt, ConfirmPrompt confirm, AutoApprove autoApprove) {
    public HarnessOptions {
        if (events == null || retry == null || prompt == null || confirm == null || autoApprove == null) {
            throw new IllegalArgumentException("HarnessOptions 的 events、retry、prompt、confirm 与 autoApprove 不能为空");
        }
    }

    public HarnessOptions(EventHub events, ModelRetryPolicy retry, BufferedReader loopInput, boolean debug,
                          PromptStyle prompt, ConfirmPrompt confirm) {
        this(events, retry, loopInput, debug, prompt, confirm, new AutoApprove(false));
    }

    public HarnessOptions(EventHub events, ModelRetryPolicy retry, BufferedReader loopInput, boolean debug) {
        this(events, retry, loopInput, debug, PromptStyle.plain(), ConfirmPrompt.of(debug, false));
    }

    /** 教程入口：完整输出。 */
    public HarnessOptions(EventHub events, ModelRetryPolicy retry, BufferedReader loopInput) {
        this(events, retry, loopInput, true);
    }
}
