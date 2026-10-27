package dev.huagent.team;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * 收件箱中的一条消息，对应 .mailboxes/&lt;to&gt;.jsonl 中的一行。
 * type 区分自由消息与协议消息；requestId 只用于关闭请求与响应的配对。
 */
public record TeamMessage(String from, String to, String type, String content, String requestId, Instant sentAt) {
    /** 成员之间的自由消息。 */
    public static final String MESSAGE = "message";
    /** 队友一轮正常结束时的最终回复，自动发给 Lead。 */
    public static final String RESULT = "result";
    /** 队友一轮没有正常结束（超限、请求失败、提示词被拦截）。 */
    public static final String ERROR = "error";
    /** 队友线程意外退出（不是响应关闭请求）。 */
    public static final String STOPPED = "stopped";
    public static final String SHUTDOWN_REQUEST = "shutdown_request";
    public static final String SHUTDOWN_RESPONSE = "shutdown_response";

    public TeamMessage {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        Objects.requireNonNull(type);
        content = content == null ? "" : content;
        sentAt = sentAt == null ? Instant.now() : sentAt;
    }

    public TeamMessage(String from, String to, String type, String content) {
        this(from, to, type, content, null, null);
    }

    String toJson() {
        JSONObject json = new JSONObject(new LinkedHashMap<>());
        json.put("from", from);
        json.put("to", to);
        json.put("type", type);
        json.put("content", content);
        if (requestId != null) {
            json.put("requestId", requestId);
        }
        json.put("sentAt", sentAt.toString());
        return JSON.toJSONString(json);
    }

    /** 解析一行；格式不对时抛 IllegalArgumentException，由收件箱跳过该行。 */
    static TeamMessage parse(String line) {
        JSONObject json;
        try {
            json = JSON.parseObject(line);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("不是有效的 JSON 对象");
        }
        if (json == null || !(json.get("from") instanceof String from) || !(json.get("to") instanceof String to)
                || !(json.get("type") instanceof String type) || !(json.get("content") instanceof String content)) {
            throw new IllegalArgumentException("缺少 from、to、type 或 content");
        }
        Object requestId = json.get("requestId");
        Instant sentAt;
        try {
            sentAt = json.get("sentAt") instanceof String text ? Instant.parse(text) : null;
        } catch (RuntimeException e) {
            sentAt = null;
        }
        return new TeamMessage(from, to, type, content, requestId instanceof String id ? id : null, sentAt);
    }
}
