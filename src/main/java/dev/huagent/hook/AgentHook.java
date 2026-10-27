package dev.huagent.hook;

import dev.huagent.model.ChatMessage;
import dev.huagent.tool.ToolCall;

import java.io.IOException;
import java.util.List;

/**
 * S04 的四个扩展点。按注册顺序同步执行，只需覆盖关心的事件。
 * 参数和消息快照不可修改；前置事件返回 null 放行，非 null（包括空串）拦截。
 */
public interface AgentHook {
    /** 用户提示词加入历史、请求模型之前；history 不包含本次 prompt。 */
    default String userPromptSubmit(String prompt, List<ChatMessage> history)
            throws IOException, InterruptedException {
        return null;
    }

    /** 已知工具执行和 S03 权限检查之前。 */
    default String preToolUse(ToolCall call) throws IOException, InterruptedException {
        return null;
    }

    /** 工具产生结果后，包括错误结果；耗时只计算工具执行，不能改写结果。 */
    default void postToolUse(ToolCall call, String result, long elapsedNanos) throws IOException {
    }

    /**
     * 本轮最终回复已保存和输出后；turn 包含本轮用户消息、工具往返和最终回复。
     * 不包含此前历史；超限、失败、中断或退出不触发，不能要求模型续跑。
     */
    default void stop(List<ChatMessage> turn) throws IOException {
    }

    /** S18：/hooks 命令显示的一行说明；默认显示实现类名，匿名类只显示“Java 回调”。 */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? "Java 回调" : "Java 回调 " + name;
    }
}
