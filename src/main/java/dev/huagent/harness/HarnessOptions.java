package dev.huagent.harness;

import java.io.BufferedReader;

/**
 * S15：集成装配的可选增强。为 null 时（各上一课入口）组装结果与 S14 完全相同；
 * 提供 events 与 retry 时启用事件唤醒、限流退避与响应式压缩。
 * loopInput 是循环专用的输入视图（可被程序事件唤醒）；确认类输入仍使用装配方法的 input。
 */
public record HarnessOptions(EventHub events, ModelRetryPolicy retry, BufferedReader loopInput) {
    public HarnessOptions {
        if (events == null || retry == null) {
            throw new IllegalArgumentException("HarnessOptions 的 events 与 retry 不能为空");
        }
    }
}
