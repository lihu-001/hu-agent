package dev.huagent.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class GoalCommandTest {

    @Test
    void bareCommandShowsStatus() {
        assertEquals(GoalCommand.Kind.STATUS, GoalCommand.parse("/goal").kind());
        assertEquals(GoalCommand.Kind.STATUS, GoalCommand.parse("  /goal   ").kind());
    }

    @Test
    void textAfterCommandSetsTrimmedCondition() {
        GoalCommand command = GoalCommand.parse("/goal  mvn test 退出码为 0，且不修改 tests/ 之外的测试 ");
        assertEquals(GoalCommand.Kind.SET, command.kind());
        assertEquals("mvn test 退出码为 0，且不修改 tests/ 之外的测试", command.condition());
        assertEquals("tab 分隔", GoalCommand.parse("/goal\ttab 分隔").condition());
    }

    @ParameterizedTest
    @ValueSource(strings = {"clear", "stop", "off", "reset", "none", "cancel", "CLEAR", " Cancel "})
    void clearAliasesAreCaseInsensitive(String alias) {
        assertEquals(GoalCommand.Kind.CLEAR, GoalCommand.parse("/goal " + alias).kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/goals", "/goalx 条件", "goal 条件", "请看 /goal", "", "   "})
    void otherTextIsNotACommand(String line) {
        assertNull(GoalCommand.parse(line));
    }

    @Test
    void nullIsNotACommand() {
        assertNull(GoalCommand.parse(null));
    }

    @Test
    void conditionLongerThanLimitIsRejected() {
        String max = "a".repeat(GoalCommand.MAX_CONDITION_CHARS);
        assertEquals(max, GoalCommand.parse("/goal " + max).condition());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GoalCommand.parse("/goal " + max + "b"));
        assertTrue(error.getMessage().contains("4000"), error.getMessage());
        assertTrue(error.getMessage().contains("目标未设置"), error.getMessage());
    }

    @Test
    void setRequiresCondition() {
        assertThrows(IllegalArgumentException.class, () -> new GoalCommand(GoalCommand.Kind.SET, " "));
    }
}
