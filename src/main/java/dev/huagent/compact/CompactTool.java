package dev.huagent.compact;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Objects;
import java.util.Set;

/** 让模型主动压缩上下文：只登记请求，在本批工具结果返回后、下一次模型请求前执行。 */
public final class CompactTool implements AgentTool {
    public static final String NAME = "compact";
    public static final int MAX_FOCUS_CHARS = 200;

    private final ContextCompactor compactor;

    public CompactTool(ContextCompactor compactor) {
        this.compactor = Objects.requireNonNull(compactor);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(NAME,
                "压缩当前对话：保存完整记录后把历史替换为摘要，释放上下文。适合长任务的阶段之间或切换话题时使用；"
                        + "focus 可说明摘要中必须保留的内容。压缩在本批工具结果返回后执行。",
                JSON.parseObject("""
                        {"type":"object","properties":{
                        "focus":{"type":"string","description":"可选，摘要需特别保留的内容"}},
                        "additionalProperties":false}
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
        if (args == null || !Set.of("focus").containsAll(args.keySet())) {
            throw new IllegalArgumentException("参数必须是只包含可选 focus 的 JSON 对象。");
        }
        Object focus = args.get("focus");
        if (focus != null && !(focus instanceof String)) {
            throw new IllegalArgumentException("focus 必须是字符串。");
        }
        String text = focus == null ? null : ((String) focus).strip();
        if (text != null && text.length() > MAX_FOCUS_CHARS) {
            throw new IllegalArgumentException("focus 最多 " + MAX_FOCUS_CHARS + " 字符。");
        }
        compactor.requestCompaction(text);
        return "已安排压缩：下一次模型请求前保存完整记录，并把对话历史替换为摘要。";
    }
}
