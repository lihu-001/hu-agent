package dev.huagent.permission;

import dev.huagent.tool.ToolCall;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * 确认时回答 a（本次会话不再询问）放行的范围，与 Claude Code 的会话内允许相近：
 * Shell、后台命令与定时计划按完整命令放行，只有同一条命令不再询问；写文件与编辑合为“文件修改”，放行后两者都不再询问；
 * 其余工具（MCP 工具、写记忆、创建队友等）按工具放行。只用于需要确认（ASK）的调用：硬拒绝与工作目录检查在此之前，照常生效。
 * key 用于比较，label 显示在确认问题里（“a=本次会话不再询问” + label），工具名与命令已在同一行的前面显示。
 */
public record SessionScope(String key, String label) {
    public static SessionScope of(ToolCall call) {
        return switch (call.name()) {
            case "shell" -> command(call, "这条命令");
            case "background_run" -> command(call, "这条后台命令");
            case "schedule_create" -> command(call, "用这条命令创建定时计划");
            case "write_file", "edit_file" -> new SessionScope("file-edit", "文件修改");
            default -> new SessionScope(call.name(), "这个工具");
        };
    }

    /** 参数已经过 PermissionPolicy 检查；万一取不到 command，退回按完整参数比较，不会放宽范围。 */
    private static SessionScope command(ToolCall call, String label) {
        String command;
        try {
            JSONObject args = JSON.parseObject(call.arguments());
            command = args == null ? null : args.getString("command");
        } catch (RuntimeException e) {
            command = null;
        }
        return new SessionScope(call.name() + "\n" + (command == null ? call.arguments() : command), label);
    }
}
