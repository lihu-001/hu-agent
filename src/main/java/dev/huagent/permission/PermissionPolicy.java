package dev.huagent.permission;

import dev.huagent.tool.ToolCall;
import dev.huagent.tool.file.WorkspaceFiles;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class PermissionPolicy {
    public enum Action {
        ALLOW, DENY, ASK
    }

    /** preview 是确认时显示的改动预览（见 ChangePreview）；只有需要确认的写文件、编辑与多行命令才有，其余为 null。 */
    public record Decision(Action action, String reason, ChangePreview preview) {
        public Decision(Action action, String reason) {
            this(action, reason, null);
        }
    }

    private record DenyRule(Pattern pattern, String reason) {
    }

    // 教学用文本规则，不解析 Shell；可能误报，也不能覆盖变形、别名或编码后的命令。
    private static final List<DenyRule> DENY_RULES = List.of(
            new DenyRule(Pattern.compile(
                    "(?i)(?<![\\w-])(?:sudo|shutdown|reboot|mkfs|stop-computer|restart-computer|"
                            + "format-volume|clear-disk|remove-partition)(?:\\.exe)?(?![\\w-])"),
                    "命中提权、关机或磁盘操作的硬拒绝规则。"),
            new DenyRule(Pattern.compile("(?i)\\brm\\s+-(?:rf|fr)\\s+/\\s*(?:$|[;&|])"),
                    "命中删除根目录的硬拒绝规则。"),
            new DenyRule(Pattern.compile("(?i)(?:\\bdd\\s+if\\s*=|>\\s*/dev/sda)"),
                    "命中直接读写磁盘的硬拒绝规则。"));

    /** 与 mcp.McpTool.PREFIX 相同；权限包不依赖 mcp 包。 */
    private static final String MCP_PREFIX = "mcp__";

    private final WorkspaceFiles files;
    private final Set<String> allowedMcpTools;

    public PermissionPolicy(WorkspaceFiles files) {
        this(files, Set.of());
    }

    /** S14：allowedMcpTools 是 mcp.json 中 allow 列出的 MCP 工具全名，其余 MCP 工具逐次确认。 */
    public PermissionPolicy(WorkspaceFiles files, Set<String> allowedMcpTools) {
        this.files = files;
        this.allowedMcpTools = Set.copyOf(allowedMcpTools);
    }

    public Decision evaluate(ToolCall call) {
        JSONObject args;
        try {
            args = JSON.parseObject(call.arguments());
        } catch (RuntimeException e) {
            return new Decision(Action.DENY, "无法检查无效的 JSON 参数。");
        }
        if (args == null) {
            return new Decision(Action.DENY, "参数必须是 JSON 对象。");
        }
        try {
            return switch (call.name()) {
                case "shell" -> shellDecision(requiredString(args, "command"));
                case "read_file", "write_file", "edit_file" -> fileDecision(call.name(), args);
                case "glob" -> new Decision(Action.ALLOW, "使用 S02 的工作目录内查找。");
                // 与 read_file 相同只读；path 仍须在工作目录内，搜索不执行命令，不需要逐次确认。
                case "grep" -> {
                    files.resolve(args.get("path") instanceof String path && !path.isBlank() ? path : ".");
                    yield new Decision(Action.ALLOW, "只读搜索工作目录内的文件。");
                }
                // S05：只替换会话内存中的任务清单，不接触文件或进程。
                case "todo_write" -> new Decision(Action.ALLOW, "只更新会话内存中的任务清单。");
                // S06：派发本身只发起模型请求；子代理的每次工具调用仍各自经过权限检查。
                case "task" -> new Decision(Action.ALLOW, "派发子代理；其工具调用另行检查权限。");
                // S07：只读取技能目录中的说明文本，不接触工作目录或进程。
                case "load_skill" -> new Decision(Action.ALLOW, "只读取技能目录中的说明文本。");
                // S08：只登记压缩请求；完整记录写入工作目录的 .transcripts，不执行命令。
                case "compact" -> new Decision(Action.ALLOW, "只压缩会话上下文，完整记录保存到工作目录。");
                // S09：读取记忆只读 .memory；保存与删除会影响以后每次会话的系统提示词，逐次确认。
                case "memory_read" -> new Decision(Action.ALLOW, "只读取工作目录 .memory 中的记忆。");
                case "memory_write" -> new Decision(Action.ASK, "将保存跨会话记忆，以后每次启动都会进入系统提示词，请核对内容。");
                case "memory_delete" -> new Decision(Action.ASK, "将永久删除一条跨会话记忆。");
                // S10：任务工具只读写工作目录 .tasks 中的任务记录，不进入系统提示词，也不执行命令。
                case "task_create", "task_update", "task_list", "task_get", "task_claim", "task_complete" ->
                        new Decision(Action.ALLOW, "只读写工作目录 .tasks 中的任务记录。");
                // S11：后台命令与 shell 相同的硬拒绝和逐次确认，启动时确认一次；查看结果只读内存。
                case "background_run" -> shellDecision(requiredString(args, "command"));
                case "background_check" -> new Decision(Action.ALLOW, "只查看本次会话后台任务的状态和输出。");
                // S12：创建计划会确认一次，之后每次到点都无人值守执行，硬拒绝规则同样适用；
                // 查看和暂停不执行命令，恢复会让命令重新无人值守运行，删除会丢失计划记录。
                case "schedule_create" -> shellDecision(requiredString(args, "command"));
                case "schedule_list", "schedule_get", "schedule_pause" ->
                        new Decision(Action.ALLOW, "只读取或暂停工作目录 .schedules 中的定时计划。");
                case "schedule_resume" -> new Decision(Action.ASK, "恢复后该计划会按 cron 无人值守执行命令。");
                case "schedule_delete" -> new Decision(Action.ASK, "将永久删除定时计划及其运行记录。");
                // S13：队友是独立调用模型的常驻线程，费用随人数增长，创建由程序要求逐次确认，不只靠提示词约定；
                // 消息、查看与关闭不执行命令也不改文件。
                case "spawn_teammate" -> new Decision(Action.ASK, "将创建一个常驻队友线程，它会独立、持续地调用模型。");
                case "send_message", "list_teammates", "shutdown_teammate" ->
                        new Decision(Action.ALLOW, "只读写团队收件箱或查看队友状态。");
                // S16：工作流工具只读写 .workflows 中的记录；步骤经子代理执行，与 task 相同，
                // 子代理的每次工具调用仍各自经过权限检查。
                case "workflow_create", "workflow_run", "workflow_list", "workflow_get", "workflow_cancel" ->
                        new Decision(Action.ALLOW, "只读写工作目录 .workflows 中的工作流；步骤经子代理执行，其工具调用另行检查权限。");
                // S14：外部 server 的工具做什么由 server 决定，程序无法判断风险，默认逐次确认；
                // 用户可在 mcp.json 的 allow 中放行（例如只读查询），放行列表随配置一起经过 MCP 信任确认。
                default -> !call.name().startsWith(MCP_PREFIX)
                        ? new Decision(Action.DENY, "没有为该工具定义权限规则。")
                        : allowedMcpTools.contains(call.name())
                        ? new Decision(Action.ALLOW, "mcp.json 中已放行的 MCP 工具。")
                        : new Decision(Action.ASK, "将调用外部 MCP server 的工具，程序无法判断它会做什么，请核对参数。");
            };
        } catch (IllegalArgumentException e) {
            return new Decision(Action.DENY, e.getMessage());
        } catch (IOException e) {
            return new Decision(Action.DENY, "无法验证文件路径（" + e.getClass().getSimpleName() + "）。");
        }
    }

    private Decision shellDecision(String command) {
        for (DenyRule rule : DENY_RULES) {
            if (rule.pattern().matcher(command).find()) {
                return new Decision(Action.DENY, rule.reason());
            }
        }
        return new Decision(Action.ASK, "Shell 以当前用户权限执行，可能修改文件或访问工作目录之外。",
                ChangePreview.command(command));
    }

    private Decision fileDecision(String tool, JSONObject args) throws IOException {
        String path = requiredString(args, "path");
        Path file = files.resolve(path);
        return "read_file".equals(tool)
                ? new Decision(Action.ALLOW, "读取工作目录内的文件。")
                : new Decision(Action.ASK, "将写入或修改文件，请核对本次路径和内容。", preview(tool, path, file, args));
    }

    /** 只用于显示：参数不完整、文件过大或不是 UTF-8 时不生成预览，确认行退回显示参数。 */
    private static ChangePreview preview(String tool, String path, Path file, JSONObject args) {
        try {
            String existing = ChangePreview.readText(file);
            if ("write_file".equals(tool)) {
                return args.get("content") instanceof String content ? ChangePreview.write(path, existing, content) : null;
            }
            return existing != null && args.get("old_text") instanceof String oldText
                    && args.get("new_text") instanceof String newText
                    ? ChangePreview.edit(path, existing, oldText, newText, Boolean.TRUE.equals(args.get("replace_all")))
                    : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private String requiredString(JSONObject args, String key) {
        if (!(args.get(key) instanceof String value) || StrUtil.isBlank(value)) {
            throw new IllegalArgumentException(key + " 必须是非空字符串。");
        }
        return value;
    }
}
