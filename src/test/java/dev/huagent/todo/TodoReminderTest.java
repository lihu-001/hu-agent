package dev.huagent.todo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TodoReminderTest {
    private final TodoList todos = new TodoList();

    @Test
    void remindsAfterThresholdStaleBatchesThenRestartsCounting() {
        TodoReminder reminder = new TodoReminder(todos, 3);
        reminder.startTurn();
        assertNull(reminder.afterToolBatch());
        assertNull(reminder.afterToolBatch());
        assertEquals(TodoReminder.TEXT, reminder.afterToolBatch());
        assertNull(reminder.afterToolBatch());
        assertNull(reminder.afterToolBatch());
        assertEquals(TodoReminder.TEXT, reminder.afterToolBatch());
    }

    @Test
    void updateResetsCounterAndCompletedListNeedsNoReminder() {
        TodoReminder reminder = new TodoReminder(todos, 2);
        reminder.startTurn();
        assertNull(reminder.afterToolBatch());
        todos.replace(List.of(new TodoItem("任务", TodoStatus.IN_PROGRESS)));
        assertNull(reminder.afterToolBatch());
        assertNull(reminder.afterToolBatch());
        assertEquals(TodoReminder.TEXT, reminder.afterToolBatch());

        todos.replace(List.of(new TodoItem("任务", TodoStatus.COMPLETED)));
        assertNull(reminder.afterToolBatch());
        assertNull(reminder.afterToolBatch());
        assertNull(reminder.afterToolBatch());
    }

    @Test
    void newTurnClearsCounter() {
        TodoReminder reminder = new TodoReminder(todos, 2);
        reminder.startTurn();
        assertNull(reminder.afterToolBatch());
        reminder.startTurn();
        assertNull(reminder.afterToolBatch());
        assertEquals(TodoReminder.TEXT, reminder.afterToolBatch());
    }

    @Test
    void rejectsNonPositiveThreshold() {
        assertThrows(IllegalArgumentException.class, () -> new TodoReminder(todos, 0));
    }
}
