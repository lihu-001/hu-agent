package dev.huagent.subagent;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.Set;

/** 父代理用来派发子任务的工具：子代理在独立历史中完成任务，只把最终总结作为工具结果交回。 */
public final class TaskTool implements AgentTool {
    public static final String NAME = "task";
    public static final int MAX_DESCRIPTION_CHARS = 40;
    public static final int MAX_PROMPT_CHARS = 8000;
    public static final int MAX_RESULT_CHARS = 12000;

    private final SubagentRunner runner;

    public TaskTool(SubagentRunner runner) {
        this.runner = Objects.requireNonNull(runner);
    }

    @Override
    public ToolDefinition definition() {
        String childTools = String.join("、", runner.definitions().stream().map(ToolDefinition::name).toList());
        return new ToolDefinition(NAME,
                "派发一个子代理独立完成子任务。子代理看不到当前对话，prompt 必须写清目标、相关路径和期望的返回内容。"
                        + "子代理可用工具：" + childTools + "；它不能再派发子代理，也不共享任务清单。"
                        + "适合需要大量查找、读取的探索任务，只返回最终总结，中间过程不进入当前对话。",
                JSON.parseObject("""
                        {"type":"object","properties":{
                        "description":{"type":"string","description":"3-10 字的任务简称，用于日志"},
                        "prompt":{"type":"string","description":"交给子代理的完整任务说明"}},
                        "required":["description","prompt"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws InterruptedException {
        JSONObject args;
        try {
            args = JSON.parseObject(arguments);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("arguments 必须是有效的 JSON 对象。");
        }
        if (args == null || !Set.of("description", "prompt").containsAll(args.keySet())) {
            throw new IllegalArgumentException("参数必须是只包含 description、prompt 的 JSON 对象。");
        }
        String description = requiredString(args, "description", MAX_DESCRIPTION_CHARS);
        String prompt = requiredString(args, "prompt", MAX_PROMPT_CHARS);
        SubagentRunner.Result result;
        try {
            result = runner.run(description, prompt);
        } catch (HttpTimeoutException e) {
            return "工具错误：子代理模型请求超时，子任务未完成，已执行的操作不会回滚。";
        } catch (IOException e) {
            return "工具错误：子代理模型请求失败（" + e.getClass().getSimpleName() + "），子任务未完成，已执行的操作不会回滚。";
        }
        String text = result.text();
        if (text.length() > MAX_RESULT_CHARS) {
            text = text.substring(0, MAX_RESULT_CHARS) + "\n…（子代理结果已截断）";
        }
        return "子代理结果（模型请求 " + result.modelCalls() + " 次，工具调用 " + result.toolCalls() + " 个）：\n" + text;
    }

    private static String requiredString(JSONObject args, String key, int maxChars) {
        if (!(args.get(key) instanceof String value) || StrUtil.isBlank(value)) {
            throw new IllegalArgumentException(key + " 必须是非空字符串。");
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxChars) {
            throw new IllegalArgumentException(key + " 最多 " + maxChars + " 字符。");
        }
        return trimmed;
    }
}
