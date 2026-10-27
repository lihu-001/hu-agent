package dev.huagent.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class GoalEvaluationTest {

    @Test
    void parsesStrictJsonObject() {
        GoalEvaluation result = GoalEvaluation.parse("{\"ok\": false, \"reason\": \" 还没有运行测试 \", "
                + "\"impossible\": false, \"extra\": 1}");
        assertFalse(result.ok());
        assertFalse(result.impossible());
        assertEquals("还没有运行测试", result.reason(), "理由去掉首尾空白，其他字段忽略");
        assertTrue(GoalEvaluation.parse("{\"ok\":true,\"reason\":\"退出码 0\",\"impossible\":false}").ok());
        assertTrue(GoalEvaluation.parse("{\"ok\":false,\"reason\":\"被拒绝\",\"impossible\":true}").impossible());
    }

    @Test
    void toleratesSingleCodeFence() {
        GoalEvaluation result = GoalEvaluation.parse("```json\n{\"ok\":true,\"reason\":\"测试通过\",\"impossible\":false}\n```");
        assertTrue(result.ok());
        assertEquals("测试通过", result.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "已经完成了",
            "[true]",
            "null",
            "{\"reason\":\"缺 ok\",\"impossible\":false}",
            "{\"ok\":\"true\",\"reason\":\"字符串布尔\",\"impossible\":false}",
            "{\"ok\":false,\"reason\":\"缺 impossible\"}",
            "{\"ok\":false,\"impossible\":false}",
            "{\"ok\":false,\"reason\":\"  \",\"impossible\":false}",
            "{\"ok\":false,\"reason\":1,\"impossible\":false}",
            "{\"ok\":true,\"reason\":\"矛盾\",\"impossible\":true}",
            "说明文字 {\"ok\":true,\"reason\":\"前面有字\",\"impossible\":false}",
            "   "})
    void rejectsMalformedReplies(String text) {
        assertThrows(IllegalArgumentException.class, () -> GoalEvaluation.parse(text));
    }

    @Test
    void rejectsNullReply() {
        assertThrows(IllegalArgumentException.class, () -> GoalEvaluation.parse(null));
    }

    @Test
    void longReasonIsTruncated() {
        String reason = "理".repeat(GoalEvaluation.MAX_REASON_CHARS + 50);
        GoalEvaluation result = GoalEvaluation.parse("{\"ok\":false,\"reason\":\"" + reason + "\",\"impossible\":false}");
        assertEquals(GoalEvaluation.MAX_REASON_CHARS + 1, result.reason().length());
        assertTrue(result.reason().endsWith("…"));
    }
}
