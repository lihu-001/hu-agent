package dev.huagent.permission;

/**
 * 自动审批模式的开关，hu --auto 启动时开启，会话中用 /auto 切换。
 * 开启时需要逐次确认（ASK）的工具调用自动批准，只显示一行记录；硬拒绝规则、工作目录路径检查与没有权限规则的
 * 工具（DENY）照常拒绝。主 Agent、子代理、工作流步骤与队友共用同一个开关，切换从下一次权限检查起生效。
 * 不影响 Hook 与 MCP 配置的信任确认：那是启动时对项目配置的一次性授权，不是逐次确认。
 */
public final class AutoApprove {
    private volatile boolean enabled;

    public AutoApprove(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void set(boolean enabled) {
        this.enabled = enabled;
    }
}
