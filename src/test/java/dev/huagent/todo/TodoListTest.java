package dev.huagent.todo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TodoListTest {
    @Test
    void replacesWholeListTrimsContentAndRendersProgress() {
        TodoList todos = new TodoList();
        assertEquals("任务清单为空。", todos.render());
        todos.replace(List.of(new TodoItem("  读取文件 ", TodoStatus.COMPLETED),
                new TodoItem("修改代码", TodoStatus.IN_PROGRESS), new TodoItem("运行测试", TodoStatus.PENDING)));
        assertEquals("读取文件", todos.items().get(0).content());
        assertEquals("任务清单（1/3 完成）：\n[x] 读取文件\n[>] 修改代码\n[ ] 运行测试", todos.render());
        assertTrue(todos.hasUnfinished());

        todos.replace(List.of(new TodoItem("运行测试", TodoStatus.COMPLETED)));
        assertEquals(1, todos.items().size());
        assertFalse(todos.hasUnfinished());
        assertEquals(2, todos.version());
    }

    @Test
    void invalidListsAreRejectedWithoutChangingState() {
        TodoList todos = new TodoList();
        todos.replace(List.of(new TodoItem("原任务", TodoStatus.PENDING)));
        List<TodoItem> tooMany = new ArrayList<>();
        for (int i = 0; i <= TodoList.MAX_ITEMS; i++) {
            tooMany.add(new TodoItem("任务" + i, TodoStatus.PENDING));
        }
        for (List<TodoItem> invalid : List.of(tooMany,
                List.of(new TodoItem("  ", TodoStatus.PENDING)),
                List.of(new TodoItem("长".repeat(TodoList.MAX_CONTENT_CHARS + 1), TodoStatus.PENDING)),
                List.of(new TodoItem("一", TodoStatus.IN_PROGRESS), new TodoItem("二", TodoStatus.IN_PROGRESS)))) {
            assertThrows(IllegalArgumentException.class, () -> todos.replace(invalid));
        }
        assertEquals(List.of(new TodoItem("原任务", TodoStatus.PENDING)), todos.items());
        assertEquals(1, todos.version());
    }

    @Test
    void emptyListClearsAndCountsAsUpdate() {
        TodoList todos = new TodoList();
        todos.replace(List.of(new TodoItem("任务", TodoStatus.PENDING)));
        todos.replace(List.of());
        assertTrue(todos.items().isEmpty());
        assertFalse(todos.hasUnfinished());
        assertEquals(2, todos.version());
    }
}
