package dev.huagent.task;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 六个任务工具：task_create、task_update、task_list、task_get、task_claim、task_complete。
 * 每次调用都读写 .tasks 中的文件；owner 由程序固定传入，模型不能冒用其他认领者。
 */
public final class TaskTools {
    public static final String CREATE = "task_create";
    public static final String UPDATE = "task_update";
    public static final String LIST = "task_list";
    public static final String GET = "task_get";
    public static final String CLAIM = "task_claim";
    public static final String COMPLETE = "task_complete";
    public static final List<String> NAMES = List.of(CREATE, UPDATE, LIST, GET, CLAIM, COMPLETE);
    public static final String DEFAULT_OWNER = "agent";

    private TaskTools() {
    }

    /** S13：队友只能查看、认领和完成，一次只持有一个任务；不能创建任务或修改依赖。 */
    public static final List<String> TEAMMATE_NAMES = List.of(LIST, GET, CLAIM, COMPLETE);

    public static List<AgentTool> create(TaskStore store, String owner) {
        return create(store, owner, false);
    }

    public static List<AgentTool> createForTeammate(TaskStore store, String owner) {
        return create(store, owner, true).stream()
                .filter(tool -> TEAMMATE_NAMES.contains(tool.definition().name())).toList();
    }

    private static List<AgentTool> create(TaskStore store, String owner, boolean single) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(owner);
        return List.of(
                tool(CREATE, "创建一个持久化任务，保存在工作目录 .tasks/，跨会话保留。新任务为 pending、没有依赖；"
                                + "返回运行时生成的 ID。先创建全部任务，再用 task_update 和返回的 ID 添加依赖。",
                        """
                        {"type":"object","properties":{
                        "subject":{"type":"string","description":"单行任务标题"},
                        "description":{"type":"string","description":"可选，完成任务所需的细节，跨会话恢复时用 task_get 读取"}},
                        "required":["subject"],"additionalProperties":false}
                        """, Set.of("subject", "description"), args -> {
                            TaskRecord task = store.create(requiredString(args, "subject"),
                                    optionalString(args, "description"));
                            return "已创建 " + task.id() + "：" + task.subject();
                        }),
                tool(UPDATE, "为 pending 任务追加前置依赖（addBlockedBy 中的任务全部完成后才能认领）。"
                                + "依赖必须已存在，不能依赖自身或形成环；整次校验通过才保存，重复添加不会产生重复边。",
                        """
                        {"type":"object","properties":{
                        "id":{"type":"string","description":"要修改的任务 ID"},
                        "addBlockedBy":{"type":"array","items":{"type":"string"},"description":"前置任务 ID 列表"}},
                        "required":["id","addBlockedBy"],"additionalProperties":false}
                        """, Set.of("id", "addBlockedBy"), args -> {
                            TaskRecord task = store.addDependencies(requiredString(args, "id"), idList(args));
                            return "已更新 " + task.id() + " 依赖："
                                    + (task.blockedBy().isEmpty() ? "无" : String.join(",", task.blockedBy()));
                        }),
                tool(LIST, "列出全部持久化任务的一行摘要：状态、认领者、依赖，以及 pending 任务是否可开始。"
                                + "开始新会话或不确定进度时先调用。",
                        """
                        {"type":"object","properties":{},"additionalProperties":false}
                        """, Set.of(), args -> {
                            List<String> warnings = new ArrayList<>();
                            String text = store.render(store.list(warnings::add));
                            return warnings.isEmpty() ? text : text + "\n" + String.join("\n", warnings);
                        }),
                tool(GET, "读取一个任务的完整 JSON，包括 description。",
                        """
                        {"type":"object","properties":{"id":{"type":"string"}},
                        "required":["id"],"additionalProperties":false}
                        """, Set.of("id"), args -> TaskStore.toJson(store.load(requiredString(args, "id"))).strip()),
                tool(CLAIM, "开始做一个任务前认领它：pending → in_progress。依赖未全部完成时拒绝"
                                + (single ? "；一次只能持有一个进行中的任务。" : "。"),
                        """
                        {"type":"object","properties":{"id":{"type":"string"}},
                        "required":["id"],"additionalProperties":false}
                        """, Set.of("id"), args -> {
                            TaskRecord task = store.claim(requiredString(args, "id"), owner, single);
                            return "已认领 " + task.id() + "：" + task.subject() + "（认领者 " + task.owner() + "）";
                        }),
                tool(COMPLETE, "任务确实做完后标记完成：in_progress → completed，只能完成自己认领的任务。"
                                + "结果列出因此刚刚可以开始的下游任务。",
                        """
                        {"type":"object","properties":{"id":{"type":"string"}},
                        "required":["id"],"additionalProperties":false}
                        """, Set.of("id"), args -> {
                            TaskStore.Completion completion = store.complete(requiredString(args, "id"), owner);
                            TaskRecord task = completion.task();
                            String text = "已完成 " + task.id() + "：" + task.subject();
                            if (!completion.unblocked().isEmpty()) {
                                text += "\n已解锁：" + String.join("、", completion.unblocked().stream()
                                        .map(next -> next.id() + "（" + next.subject() + "）").toList());
                            }
                            return text;
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

    private static String optionalString(JSONObject args, String key) {
        Object value = args.get(key);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException(key + " 必须是字符串。");
        }
        return (String) value;
    }

    private static List<String> idList(JSONObject args) {
        if (!(args.get("addBlockedBy") instanceof JSONArray array) || array.isEmpty()) {
            throw new IllegalArgumentException("addBlockedBy 必须是非空的任务 ID 数组。");
        }
        List<String> ids = new ArrayList<>();
        for (Object element : array) {
            if (!(element instanceof String id)) {
                throw new IllegalArgumentException("addBlockedBy 只能包含任务 ID 字符串。");
            }
            ids.add(id.strip());
        }
        return ids;
    }
}
