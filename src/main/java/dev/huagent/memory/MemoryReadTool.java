package dev.huagent.memory;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/** 按名称读取一条记忆的正文；系统提示词只有索引，正文按需读取。 */
public final class MemoryReadTool implements AgentTool {
    public static final String NAME = "memory_read";

    private final MemoryStore store;

    public MemoryReadTool(MemoryStore store) {
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(NAME,
                "读取一条跨会话记忆的完整内容。系统提示词只列出记忆名称和描述，与当前任务相关时再读取。",
                JSON.parseObject("""
                        {"type":"object","properties":{
                        "name":{"type":"string","description":"记忆名称，取自系统提示词中的记忆索引"}},
                        "required":["name"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws IOException {
        JSONObject args = MemoryArguments.parse(arguments, Set.of("name"));
        return store.read(MemoryArguments.requiredString(args, "name"));
    }
}
