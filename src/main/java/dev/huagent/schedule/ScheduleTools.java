package dev.huagent.schedule;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 六个定时计划工具：create、list、get、pause、resume、delete。
 * 计划保存在工作目录 .schedules/，只在当前 Agent 进程运行期间逐分钟检查；命令到点后由后台执行，
 * 输出写回计划文件，用 get 查看，不进入对话通知。
 */
public final class ScheduleTools {
    public static final String CREATE = "schedule_create";
    public static final String LIST = "schedule_list";
    public static final String GET = "schedule_get";
    public static final String PAUSE = "schedule_pause";
    public static final String RESUME = "schedule_resume";
    public static final String DELETE = "schedule_delete";
    public static final List<String> NAMES = List.of(CREATE, LIST, GET, PAUSE, RESUME, DELETE);

    private ScheduleTools() {
    }

    public static List<AgentTool> create(ScheduleManager manager, String shellDescription) {
        Objects.requireNonNull(manager);
        return List.of(
                tool(CREATE, "创建一个持久化的定时计划：用五段 cron（分 时 日 月 周）描述时间，到点在后台执行一条 Shell 命令。"
                                + "字段语法由 Hutool CronPattern 解析；日与周同时指定时按“或”匹配。"
                                + "时间使用本机时区，最短间隔一分钟。计划存到 .schedules/，跨会话保留，但只有本程序运行时才会触发"
                                + "（不是操作系统定时任务）；进程没运行时错过的时点不补跑。命令以当前用户权限无人值守执行："
                                + "创建时确认一次，之后每次触发不再询问，只调度你愿意让它自行执行的命令。执行环境与 shell 相同："
                                + shellDescription + " 结果和状态写回计划文件，用 schedule_get 查看。",
                        """
                        {"type":"object","properties":{
                        "name":{"type":"string","description":"单行计划名称，便于识别"},
                        "cron":{"type":"string","description":"五段 cron，例如 0 9 * * 1-5 表示工作日 9:00"},
                        "command":{"type":"string","description":"到点后要执行的 Shell 命令"}},
                        "required":["name","cron","command"],"additionalProperties":false}
                        """, Set.of("name", "cron", "command"), args -> {
                            ScheduleRecord schedule = manager.create(requiredString(args, "name"),
                                    requiredString(args, "cron"), requiredString(args, "command"));
                            return "已创建 " + schedule.id() + "：" + schedule.name()
                                    + "\ncron=" + schedule.cron() + "，下一次 "
                                    + ScheduleStore.time(schedule.nextRunAt(), manager.zone())
                                    + "；到点结果用 schedule_get 查看。";
                        }),
                tool(LIST, "列出全部持久化定时计划：启停状态、ID、名称、cron、下一次时间、上次结果与触发次数。",
                        """
                        {"type":"object","properties":{},"additionalProperties":false}
                        """, Set.of(), args -> {
                            List<String> warnings = new ArrayList<>();
                            String text = ScheduleStore.render(manager.list(warnings::add), manager.zone());
                            return warnings.isEmpty() ? text : text + "\n" + String.join("\n", warnings);
                        }),
                tool(GET, "读取一个定时计划的完整 JSON，包括上次运行状态、时间和输出。",
                        """
                        {"type":"object","properties":{"id":{"type":"string","description":"计划 ID，例如 schedule_0a1b2c3d"}},
                        "required":["id"],"additionalProperties":false}
                        """, Set.of("id"), args -> ScheduleStore.toJson(
                                manager.get(requiredString(args, "id"))).strip()),
                tool(PAUSE, "暂停一个定时计划：不再触发新的运行，设置保留；已经在执行的命令会继续跑完。",
                        """
                        {"type":"object","properties":{"id":{"type":"string"}},
                        "required":["id"],"additionalProperties":false}
                        """, Set.of("id"), args -> {
                            ScheduleRecord schedule = manager.pause(requiredString(args, "id"));
                            return "已暂停 " + schedule.id() + "：" + schedule.name() + "，不会再触发新的运行。";
                        }),
                tool(RESUME, "恢复一个已暂停的计划：从当前时间重新计算下一次，不补跑暂停期间错过的时点。",
                        """
                        {"type":"object","properties":{"id":{"type":"string"}},
                        "required":["id"],"additionalProperties":false}
                        """, Set.of("id"), args -> {
                            ScheduleRecord schedule = manager.resume(requiredString(args, "id"));
                            return "已恢复 " + schedule.id() + "：" + schedule.name() + "，下一次 "
                                    + ScheduleStore.time(schedule.nextRunAt(), manager.zone());
                        }),
                tool(DELETE, "永久删除一个定时计划及其运行记录。正在运行的计划不能删除。",
                        """
                        {"type":"object","properties":{"id":{"type":"string"}},
                        "required":["id"],"additionalProperties":false}
                        """, Set.of("id"), args -> {
                            String id = requiredString(args, "id");
                            manager.delete(id);
                            return "已删除 " + id + "。";
                        }));
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
}
