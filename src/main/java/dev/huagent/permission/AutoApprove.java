package dev.huagent.permission;

import dev.huagent.tool.ToolCall;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动审批模式的开关，hu 启动时默认开启（hu --no-auto 不开启），会话中用 /auto 切换。
 * 开启时需要逐次确认（ASK）的工具调用自动批准，只显示一行记录；硬拒绝规则、工作目录路径检查与没有权限规则的
 * 工具（DENY）照常拒绝。主 Agent、子代理、工作流步骤与队友共用同一个开关，切换从下一次权限检查起生效。
 * 不影响 Hook 与 MCP 配置的信任确认：那是启动时对项目配置的一次性授权，不是逐次确认。
 * <p>同时记下手动确认时回答 a（本次会话不再询问）放行的范围（见 SessionScope），同样各方共用；
 * 只在内存里，退出 hu 即失效，与开关无关，/auto off 不清除。
 */
public final class AutoApprove {
    private volatile boolean enabled;
    private final Set<String> session = ConcurrentHashMap.newKeySet();

    public AutoApprove(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void set(boolean enabled) {
        this.enabled = enabled;
    }

    /** 这次调用是否在本次会话已放行的范围内。 */
    public boolean allowedInSession(ToolCall call) {
        return session.contains(SessionScope.of(call).key());
    }

    /** 放行这次调用所在的范围，本次会话之后不再询问。 */
    public void allowInSession(ToolCall call) {
        session.add(SessionScope.of(call).key());
    }
}
