package dev.huagent.workflow;

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
 * 五个工作流工具：workflow_create、workflow_run、workflow_list、workflow_get、workflow_cancel。
 * 只读写 .workflows 中的记录；执行经 WorkflowRunner 与子代理进行，子代理的工具调用另行检查权限。
 * 整体输出超过 12000 字符时截断。
 */
public final class WorkflowTools {
    public static final String CREATE = "workflow_create";
    public static final String RUN = "workflow_run";
    public static final String LIST = "workflow_list";
    public static final String GET = "workflow_get";
    public static final String CANCEL = "workflow_cancel";
    public static final List<String> NAMES = List.of(CREATE, RUN, LIST, GET, CANCEL);
    public static final int MAX_OUTPUT_CHARS = 12000;

    /** 系统提示词规则：说明声明、执行、检查点与断点续跑的语义。 */
    public static final String RULE = "需要按固定顺序执行的多步任务（步骤现在就能确定、不需要根据中间结果改变顺序）"
            + "用 workflow_create 声明为工作流：name 加有序的 steps，每步一句交给子代理的任务说明。"
            + "workflow_run 从第一个未完成的步骤开始逐步执行，每步是一个独立子代理（看不到主对话），"
            + "前序步骤的结果会自动附在后续步骤的说明里；每步完成后进度落盘，进程退出或步骤失败后再次 workflow_run"
            + " 会从断点继续，失败的步骤会重跑、副作用可能重复，步骤说明尽量写成可重复执行。"
            + "workflow_list / workflow_get 查看进度，workflow_cancel 取消后不能再继续。"
            + "执行期间子代理的写文件和 Shell 仍会逐次请求用户确认。";

    private WorkflowTools() {
    }

    public static List<AgentTool> create(WorkflowStore store, WorkflowRunner runner) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(runner);
        return List.of(
                tool(CREATE, "声明一个持久化工作流：name 加有序的 steps（每步一句交给子代理的任务说明），"
                                + "保存在工作目录 .workflows/，跨会话保留。创建后为 pending，用 workflow_run 开始执行。",
                        """
                        {"type":"object","properties":{
                        "name":{"type":"string","description":"单行工作流名称"},
                        "steps":{"type":"array","items":{"type":"string"},
                        "description":"有序步骤列表，每步是交给子代理的完整任务说明；1-20 步"}},
                        "required":["name","steps"],"additionalProperties":false}
                        """, Set.of("name", "steps"), args -> {
                            WorkflowRecord workflow = store.create(requiredString(args, "name"),
                                    promptList(args), runner.now());
                            StringBuilder text = new StringBuilder("已创建 ").append(workflow.id()).append("：")
                                    .append(workflow.name()).append("（共 ").append(workflow.steps().size())
                                    .append(" 步，状态 pending）");
                            for (int i = 0; i < workflow.steps().size(); i++) {
                                text.append("\n[ ] 步骤 ").append(i + 1).append("：")
                                        .append(workflow.steps().get(i).prompt());
                            }
                            return text.toString();
                        }),
                tool(RUN, "运行一个工作流：从第一个未完成的步骤开始逐步执行（每步通过独立子代理完成，"
                                + "前序结果自动传递），每步完成后保存进度；进程退出或失败后再次调用会从断点继续。"
                                + "子代理的写文件和 Shell 仍会逐次请求用户确认。",
                        """
                        {"type":"object","properties":{"id":{"type":"string"}},
                        "required":["id"],"additionalProperties":false}
                        """, Set.of("id"), args -> truncate(runner.run(requiredString(args, "id")).render())),
                tool(LIST, "列出全部工作流的一行摘要：状态、完成进度与最近错误。",
                        """
                        {"type":"object","properties":{},"additionalProperties":false}
                        """, Set.of(), args -> {
                            List<String> warnings = new ArrayList<>();
                            String text = WorkflowStore.render(store.list(warnings::add));
                            return warnings.isEmpty() ? text : text + "\n" + String.join("\n", warnings);
                        }),
                tool(GET, "读取一个工作流的完整 JSON：每步的说明、状态、结果与起止时间。",
                        """
                        {"type":"object","properties":{"id":{"type":"string"}},
                        "required":["id"],"additionalProperties":false}
                        """, Set.of("id"), args -> WorkflowStore.toJson(store.load(requiredString(args, "id"))).strip()),
                tool(CANCEL, "取消一个工作流：pending、paused 或 failed 时标记为 cancelled，不能再继续。"
                                + "已完成的工作流和正在运行的工作流不能取消。",
                        """
                        {"type":"object","properties":{"id":{"type":"string"}},
                        "required":["id"],"additionalProperties":false}
                        """, Set.of("id"), args -> {
                            WorkflowRecord workflow = store.load(requiredString(args, "id"));
                            if (workflow.status() == WorkflowStatus.RUNNING) {
                                throw new IllegalArgumentException("工作流正在执行，不能取消；等本次运行结束后再取消");
                            }
                            if (workflow.status() == WorkflowStatus.COMPLETED) {
                                throw new IllegalArgumentException("工作流已全部完成，不能取消");
                            }
                            if (workflow.status() == WorkflowStatus.CANCELLED) {
                                return "工作流 " + workflow.id() + " 已是取消状态。";
                            }
                            WorkflowRecord cancelled = workflow.withStatus(WorkflowStatus.CANCELLED, runner.now());
                            store.save(cancelled);
                            return "已取消 " + cancelled.id() + "：" + cancelled.name() + "（完成 "
                                    + cancelled.completedCount() + "/" + cancelled.steps().size() + " 步，不能继续）";
                        }));
    }

    @FunctionalInterface
    private interface Action {
        String run(JSONObject args) throws IOException, InterruptedException;
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
            public String execute(String arguments) throws IOException, InterruptedException {
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

    private static List<String> promptList(JSONObject args) {
        if (!(args.get("steps") instanceof JSONArray array) || array.isEmpty()) {
            throw new IllegalArgumentException("steps 必须是非空的步骤数组。");
        }
        List<String> prompts = new ArrayList<>();
        for (Object element : array) {
            if (!(element instanceof String prompt) || StrUtil.isBlank(prompt)) {
                throw new IllegalArgumentException("steps 只能包含非空字符串。");
            }
            prompts.add(prompt.strip());
        }
        return prompts;
    }

    private static String truncate(String text) {
        return text.length() <= MAX_OUTPUT_CHARS
                ? text : text.substring(0, MAX_OUTPUT_CHARS) + "\n…（工作流输出已截断）";
    }
}
