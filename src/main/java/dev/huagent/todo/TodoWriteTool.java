package dev.huagent.todo;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 模型用来提交完整任务清单的工具；结果是渲染后的清单，交还模型继续参考。 */
public final class TodoWriteTool implements AgentTool {
    public static final String NAME = "todo_write";

    private final TodoList todos;

    public TodoWriteTool(TodoList todos) {
        this.todos = Objects.requireNonNull(todos);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(NAME,
                "更新本次会话的任务清单。每次提交完整清单，整体替换旧清单；空数组表示清空。"
                        + "status 为 pending、in_progress 或 completed，同一时间最多一项 in_progress。"
                        + "最多 " + TodoList.MAX_ITEMS + " 项，只保存在内存中。",
                JSON.parseObject("""
                        {"type":"object","properties":{"todos":{"type":"array","items":{"type":"object",
                        "properties":{"content":{"type":"string"},
                        "status":{"type":"string","enum":["pending","in_progress","completed"]}},
                        "required":["content","status"],"additionalProperties":false}}},
                        "required":["todos"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) {
        JSONObject args;
        try {
            args = JSON.parseObject(arguments);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("arguments 必须是有效的 JSON 对象。");
        }
        if (args == null || !Set.of("todos").containsAll(args.keySet())
                || !(args.get("todos") instanceof JSONArray array)) {
            throw new IllegalArgumentException("参数必须是只包含 todos 数组的 JSON 对象。");
        }
        List<TodoItem> items = new ArrayList<>();
        for (Object element : array) {
            if (!(element instanceof JSONObject item) || !Set.of("content", "status").containsAll(item.keySet())
                    || !(item.get("content") instanceof String content)
                    || !(item.get("status") instanceof String status)) {
                throw new IllegalArgumentException("每项必须是只包含 content、status 字符串的对象。");
            }
            items.add(new TodoItem(content, TodoStatus.parse(status)));
        }
        todos.replace(items);
        return "已更新。" + todos.render();
    }
}
