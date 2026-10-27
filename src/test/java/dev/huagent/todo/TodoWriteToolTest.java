package dev.huagent.todo;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TodoWriteToolTest {
    private final TodoList todos = new TodoList();
    private final TodoWriteTool tool = new TodoWriteTool(todos);

    @Test
    void definitionDeclaresTodosArrayWithStatusEnum() {
        assertEquals("todo_write", tool.definition().name());
        JSONObject parameters = tool.definition().parameters();
        JSONObject item = parameters.getJSONObject("properties").getJSONObject("todos").getJSONObject("items");
        assertEquals(List.of("pending", "in_progress", "completed"),
                item.getJSONObject("properties").getJSONObject("status").getJSONArray("enum").toJavaList(String.class));
        assertEquals(List.of("todos"), parameters.getJSONArray("required").toJavaList(String.class));
    }

    @Test
    void executesAndReturnsRenderedList() {
        String result = tool.execute("{\"todos\":[{\"content\":\"写代码\",\"status\":\"in_progress\"},"
                + "{\"content\":\"测试\",\"status\":\"pending\"}]}");
        assertEquals("已更新。任务清单（0/2 完成）：\n[>] 写代码\n[ ] 测试", result);
        assertEquals(TodoStatus.IN_PROGRESS, todos.items().get(0).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"不是 JSON", "[]", "{}", "{\"todos\":{}}", "{\"todos\":[],\"extra\":1}",
            "{\"todos\":[\"文本\"]}", "{\"todos\":[{\"content\":\"a\"}]}",
            "{\"todos\":[{\"content\":1,\"status\":\"pending\"}]}",
            "{\"todos\":[{\"content\":\"a\",\"status\":\"done\"}]}",
            "{\"todos\":[{\"content\":\"a\",\"status\":\"pending\",\"id\":1}]}"})
    void rejectsInvalidArgumentsWithoutChangingList(String arguments) {
        tool.execute("{\"todos\":[{\"content\":\"原任务\",\"status\":\"pending\"}]}");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments));
        assertEquals("原任务", todos.items().get(0).content());
        assertEquals(1, todos.version());
    }
}
