package dev.huagent.harness;

import dev.huagent.hook.AgentHook;
import dev.huagent.model.ChatMessage;

import cn.hutool.core.util.StrUtil;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.List;
import java.util.Objects;

/**
 * hu -p 的一次非交互运行（与 Claude Code 的 -p 相同）：任务作为唯一一次输入交给主 Agent 的对话循环，
 * 这一轮结束后输入结束，会话随之结束。它同时是一个 Hook，从 Stop 事件取本轮的最终回答。
 * 最终回答单独写到标准输出，便于脚本与评测框架读取；过程写到标准错误（见 Transcript.headless）。
 * 请求失败、达到模型调用上限、提示词被 Hook 拒绝或只执行了本地斜杠命令时不触发 Stop，模型最后没有文字回答时
 * 也算没有最终回答。
 */
public final class PrintMode implements AgentHook {
    private final String task;
    private String answer;

    public PrintMode(String task) {
        this.task = Objects.requireNonNull(task);
    }

    /** 确认类输入：没有回答可读，需要确认的工具调用、没有信任记录的 Hook 与 MCP server 都按拒绝处理。 */
    public static BufferedReader noAnswers() {
        return new BufferedReader(Reader.nullReader());
    }

    /** 对话循环的输入：第一次读取返回任务（可以有多行，与行编辑器整段返回的粘贴相同），之后输入结束。 */
    public BufferedReader input() {
        return new BufferedReader(Reader.nullReader()) {
            private boolean read;

            @Override
            public String readLine() {
                if (read) {
                    return null;
                }
                read = true;
                return task;
            }
        };
    }

    /** 本轮最后一条消息是最终回答；没有文字时（空回复）不算。 */
    @Override
    public void stop(List<ChatMessage> turn) {
        ChatMessage last = turn.getLast();
        answer = "assistant".equals(last.role()) && StrUtil.isNotBlank(last.content()) ? last.content() : null;
    }

    /** 最终回答；还没有时为 null。 */
    public String answer() {
        return answer;
    }

    /** 会话结束后调用，返回进程退出码：有最终回答时写到 result 并返回 0，否则在 progress 上说明并返回 1。 */
    public int finish(PrintWriter result, PrintWriter progress) {
        if (answer == null) {
            progress.println("hu: 本轮没有得到最终回答（原因见上方输出）。");
            progress.flush();
            return 1;
        }
        result.println(answer);
        result.flush();
        return 0;
    }
}
