package dev.huagent.background;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** background_run 启动后台命令并立即返回 ID；background_check 查看一个或全部后台任务。 */
public final class BackgroundTools {
    public static final String RUN = "background_run";
    public static final String CHECK = "background_check";
    public static final List<String> NAMES = List.of(RUN, CHECK);

    private BackgroundTools() {
    }

    public static List<AgentTool> create(BackgroundManager manager, String shellDescription) {
        Objects.requireNonNull(manager);
        return List.of(
                tool(RUN, "在后台执行一条耗时命令（安装依赖、构建、测试等），立即返回任务 ID，不等待结束。"
                                + "执行环境与 shell 相同：" + shellDescription
                                + " 完成后结果会在之后的某次请求中以 <background-results> 自动送达，不需要轮询；"
                                + "等待期间可以继续做别的事。需要立即得到结果的短命令直接用 shell。",
                        """
                        {"type":"object","properties":{"command":{"type":"string","description":"要在后台执行的命令"}},
                         "required":["command"],"additionalProperties":false}
                        """, Set.of("command"), args -> {
                            BackgroundTask task = manager.start(requiredString(args, "command"));
                            return "已在后台启动 " + task.id() + "：" + task.command()
                                    + "\n完成后自动通知，可继续其他工作。";
                        }),
                tool(CHECK, "查看后台任务：给出 id 时返回该任务的状态和完整输出；省略 id 时列出全部任务的状态。",
                        """
                        {"type":"object","properties":{"id":{"type":"string","description":"可选，后台任务 ID，例如 bg_1"}},
                         "additionalProperties":false}
                        """, Set.of("id"), args -> {
                            Object id = args.get("id");
                            if (id == null) {
                                return render(manager.list());
                            }
                            if (!(id instanceof String value) || StrUtil.isBlank(value)) {
                                throw new IllegalArgumentException("id 必须是非空字符串。");
                            }
                            BackgroundTask task = manager.get(value.strip());
                            return line(task) + "\n" + (task.result() == null ? "（仍在运行，暂无输出）" : task.result());
                        }));
    }

    /** S18：/bg 命令与 background_check 共用同一渲染。 */
    public static String render(List<BackgroundTask> tasks) {
        if (tasks.isEmpty()) {
            return "本次会话没有后台任务。";
        }
        return String.join("\n", tasks.stream().map(BackgroundTools::line).toList());
    }

    private static String line(BackgroundTask task) {
        return task.id() + " [" + task.status().label() + "] " + task.elapsedMillis() + " ms：" + task.command();
    }

    private static AgentTool tool(String name, String description, String schema, Set<String> allowed,
                                  Function<JSONObject, String> action) {
        ToolDefinition definition = new ToolDefinition(name, description, JSON.parseObject(schema));
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public String execute(String arguments) {
                return action.apply(parse(arguments, allowed));
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
            throw new IllegalArgumentException("参数只能包含 " + String.join("、", allowed.stream().sorted().toList()) + "。");
        }
        return args;
    }

    private static String requiredString(JSONObject args, String key) {
        if (!(args.get(key) instanceof String value) || StrUtil.isBlank(value)) {
            throw new IllegalArgumentException(key + " 必须是非空字符串。");
        }
        return value;
    }
}
