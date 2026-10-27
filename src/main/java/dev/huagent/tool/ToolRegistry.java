package dev.huagent.tool;

import dev.huagent.hook.AgentHook;
import dev.huagent.hook.AgentHooks;
import dev.huagent.permission.ToolPermission;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final ToolPermission permission;
    private final AgentHooks hooks;

    public ToolRegistry(List<AgentTool> tools) {
        this(tools, call -> null);
    }

    public ToolRegistry(List<AgentTool> tools, ToolPermission permission) {
        this(tools, permission, AgentHooks.empty());
    }

    public ToolRegistry(List<AgentTool> tools, ToolPermission permission,
                        List<? extends AgentHook> hooks, Consumer<String> hookWarnings) {
        this(tools, permission, new AgentHooks(hooks, hookWarnings));
    }

    public ToolRegistry(List<AgentTool> tools, ToolPermission permission, AgentHooks hooks) {
        this.permission = Objects.requireNonNull(permission);
        this.hooks = Objects.requireNonNull(hooks);
        for (AgentTool tool : tools) {
            String name = tool.definition().name();
            if (name == null || name.isBlank() || this.tools.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("工具名称必须非空且不能重复");
            }
        }
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }

    public String execute(ToolCall call) throws InterruptedException {
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return "工具错误：未知工具 " + call.name();
        }
        // S04 的前置回调只能进一步限制调用，不能跳过后面的 S03 权限检查。
        String blocked = hooks.preToolUse(call);
        if (blocked != null) {
            return "钩子拒绝：" + blocked + " 工具未执行。";
        }
        // S03 在已有分派点增加检查；失败时不调用 execute，也不影响调用 ID 的回传。
        try {
            String reason = permission.denialReason(call);
            if (reason != null) {
                return "权限拒绝：" + reason + " 工具未执行。";
            }
        } catch (IOException | RuntimeException e) {
            return "权限拒绝：无法完成权限检查（" + e.getClass().getSimpleName() + "），工具未执行。";
        }
        long started = System.nanoTime();
        String result;
        try {
            result = tool.execute(call.arguments());
        } catch (IllegalArgumentException e) {
            result = "工具错误：" + e.getMessage();
        } catch (IOException e) {
            result = "工具错误：文件或命令操作失败（" + e.getClass().getSimpleName() + "）。";
        } catch (RuntimeException e) {
            result = "工具错误：执行异常（" + e.getClass().getSimpleName() + "），操作可能已部分完成。";
        }
        long elapsedNanos = System.nanoTime() - started;
        hooks.postToolUse(call, result, elapsedNanos);
        return result;
    }
}
