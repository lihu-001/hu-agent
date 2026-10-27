package dev.huagent.goal;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;

import java.util.Objects;

/**
 * 判断器的结构化结果：ok 表示条件已满足，impossible 表示依据记录已无法完成，reason 是简短理由。
 * 两者不能同时为 true。
 */
public record GoalEvaluation(boolean ok, String reason, boolean impossible) {
    public static final int MAX_REASON_CHARS = 500;

    public GoalEvaluation {
        Objects.requireNonNull(reason);
        if (ok && impossible) {
            throw new IllegalArgumentException("ok 与 impossible 不能同时为 true");
        }
    }

    /**
     * 严格解析模型回复：必须是一个 JSON 对象，ok/impossible 为布尔、reason 为非空字符串，其他字段忽略。
     * 只容忍整段被一个 ``` 代码块包住；理由超过 500 字符截断。格式不符时抛出 IllegalArgumentException。
     */
    public static GoalEvaluation parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("判断器回复为空");
        }
        String body = unfence(text.strip());
        JSONObject object;
        try {
            Object parsed = JSON.parse(body);
            if (!(parsed instanceof JSONObject json)) {
                throw new IllegalArgumentException("判断器回复不是 JSON 对象");
            }
            object = json;
        } catch (JSONException e) {
            throw new IllegalArgumentException("判断器回复不是合法 JSON");
        }
        if (!(object.get("ok") instanceof Boolean ok)) {
            throw new IllegalArgumentException("判断器回复缺少布尔字段 ok");
        }
        if (!(object.get("impossible") instanceof Boolean impossible)) {
            throw new IllegalArgumentException("判断器回复缺少布尔字段 impossible");
        }
        if (!(object.get("reason") instanceof String reason) || reason.isBlank()) {
            throw new IllegalArgumentException("判断器回复缺少非空字段 reason");
        }
        if (ok && impossible) {
            throw new IllegalArgumentException("判断器回复的 ok 与 impossible 同时为 true");
        }
        String trimmed = reason.strip();
        if (trimmed.length() > MAX_REASON_CHARS) {
            trimmed = trimmed.substring(0, MAX_REASON_CHARS) + "…";
        }
        return new GoalEvaluation(ok, trimmed, impossible);
    }

    private static String unfence(String text) {
        if (!text.startsWith("```") || !text.endsWith("```") || text.length() < 6) {
            return text;
        }
        int firstLine = text.indexOf('\n');
        if (firstLine < 0) {
            return text;
        }
        return text.substring(firstLine + 1, text.length() - 3).strip();
    }
}
