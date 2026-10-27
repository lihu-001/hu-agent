package dev.huagent.team;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 团队工具。Lead：spawn_teammate、send_message、list_teammates、shutdown_teammate；
 * 队友：只有 send_message。发送者由程序固定传入，模型不能冒用其他成员的名义。
 * 没有读取收件箱的工具：消息由运行时在下一轮前自动送达。
 */
public final class TeamTools {
    public static final String SPAWN = "spawn_teammate";
    public static final String SEND = "send_message";
    public static final String LIST = "list_teammates";
    public static final String SHUTDOWN = "shutdown_teammate";
    public static final List<String> LEAD_NAMES = List.of(SPAWN, SEND, LIST, SHUTDOWN);

    private TeamTools() {
    }

    public static List<AgentTool> forLead(TeamManager team) {
        Objects.requireNonNull(team);
        return List.of(
                tool(SPAWN, "创建一个常驻队友：独立线程上的完整 Agent，有自己的历史和任务清单，与你并行工作，"
                                + "看不到当前对话，只看到 prompt。可用 task_id 分配一个 pending 任务（创建前先认领）。"
                                + "同时最多 " + TeamManager.MAX_TEAMMATES + " 个；先向用户说明组队方案，用户同意后再调用。"
                                + "worktree=true 时队友在独立的 git worktree（" + WorktreeManager.DIRECTORY_NAME
                                + "/<name>，分支 " + WorktreeManager.BRANCH_PREFIX + "<name>）中工作，只在工作目录是 git 仓库根时可用。",
                        """
                        {"type":"object","properties":{
                        "name":{"type":"string","description":"小写字母开头，只含小写字母、数字、_、-，例如 backend"},
                        "role":{"type":"string","description":"一句话角色说明，例如 负责后端接口与测试"},
                        "prompt":{"type":"string","description":"交给队友的第一条指令：目标、范围、约束和完成标准"},
                        "task_id":{"type":"string","description":"可选，分配给队友的 pending 任务 ID"},
                        "worktree":{"type":"boolean","description":"可选，为队友创建独立 git worktree，避免与其他成员改同一份文件"}},
                        "required":["name","role","prompt"],"additionalProperties":false}
                        """, Set.of("name", "role", "prompt", "task_id", "worktree"), args -> {
                            Teammate teammate = team.spawn(requiredString(args, "name"), requiredString(args, "role"),
                                    requiredString(args, "prompt"), optionalString(args, "task_id"),
                                    optionalBoolean(args, "worktree"));
                            String place = teammate.worktree() == null ? "与你共用工作目录"
                                    : "在 " + teammate.worktree().path() + "（分支 " + teammate.worktree().branch()
                                    + "，基于 " + teammate.worktree().base() + "）";
                            return "已创建队友 " + teammate.name() + "（" + teammate.role() + "），" + place + "，正在后台工作。"
                                    + "它每轮的结果会以 <team-events> 自动送达，不需要轮询。";
                        }),
                send(team, TeamManager.LEAD),
                tool(LIST, "列出活跃队友：名称、角色、状态（working/idle）和进行中的任务。结果会自动送达，不要用它轮询。",
                        """
                        {"type":"object","properties":{},"additionalProperties":false}
                        """, Set.of(), args -> team.render()),
                tool(SHUTDOWN, "请求关闭一个队友：它做完当前这一轮后退出，手上未完成的任务退回 pending。",
                        """
                        {"type":"object","properties":{"name":{"type":"string"}},
                        "required":["name"],"additionalProperties":false}
                        """, Set.of("name"), args -> {
                            String name = requiredString(args, "name");
                            String requestId = team.requestShutdown(name);
                            return "已向 " + name + " 发送关闭请求 " + requestId + "，确认会以 <team-events> 送达。";
                        }));
    }

    public static List<AgentTool> forTeammate(TeamManager team, String self) {
        return List.of(send(Objects.requireNonNull(team), Objects.requireNonNull(self)));
    }

    private static AgentTool send(TeamManager team, String from) {
        String target = TeamManager.LEAD.equals(from) ? "某个队友" : "lead 或其他队友";
        return tool(SEND, "给" + target + "发一条消息，写入对方收件箱，对方下一轮开始前自动收到。",
                """
                {"type":"object","properties":{
                "to":{"type":"string","description":"收件人：lead 或队友名"},
                "content":{"type":"string","description":"消息内容"}},
                "required":["to","content"],"additionalProperties":false}
                """, Set.of("to", "content"), args -> {
                    String to = requiredString(args, "to");
                    team.send(from, to, requiredString(args, "content"));
                    return "已发送给 " + to + "。";
                });
    }

    @FunctionalInterface
    private interface Action {
        String run(JSONObject args) throws IOException;
    }

    private static AgentTool tool(String name, String description, String schema, Set<String> allowed,
                                  Action action) {
        ToolDefinition definition = new ToolDefinition(name, description, JSON.parseObject(schema));
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public String execute(String arguments) throws IOException {
                return action.run(parse(arguments, allowed));
            }
        };
    }

    private static JSONObject parse(String arguments, Set<String> allowed) {
        JSONObject args;
        try {
            args = JSON.parseObject(StrUtil.isBlank(arguments) ? "{}" : arguments);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("arguments 必须是有效的 JSON 对象。");
        }
        if (args == null || !allowed.containsAll(args.keySet())) {
            throw new IllegalArgumentException(allowed.isEmpty() ? "该工具没有参数。"
                    : "参数只能包含 " + String.join("、", allowed.stream().sorted().toList()) + "。");
        }
        return args;
    }

    private static String requiredString(JSONObject args, String key) {
        if (!(args.get(key) instanceof String value) || StrUtil.isBlank(value)) {
            throw new IllegalArgumentException(key + " 必须是非空字符串。");
        }
        return value.strip();
    }

    private static boolean optionalBoolean(JSONObject args, String key) {
        Object value = args.get(key);
        if (value != null && !(value instanceof Boolean)) {
            throw new IllegalArgumentException(key + " 必须是布尔值。");
        }
        return Boolean.TRUE.equals(value);
    }

    private static String optionalString(JSONObject args, String key) {
        Object value = args.get(key);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException(key + " 必须是字符串。");
        }
        return value == null || ((String) value).isBlank() ? null : ((String) value).strip();
    }
}
