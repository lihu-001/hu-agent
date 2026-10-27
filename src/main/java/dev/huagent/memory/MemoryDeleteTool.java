package dev.huagent.memory;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/** 删除一条已过时或错误的记忆。 */
public final class MemoryDeleteTool implements AgentTool {
    public static final String NAME = "memory_delete";

    private final MemoryStore store;

    public MemoryDeleteTool(MemoryStore store) {
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(NAME,
                "删除一条已过时或被证明错误的跨会话记忆。",
                JSON.parseObject("""
                        {"type":"object","properties":{
                        "name":{"type":"string","description":"要删除的记忆名称"}},
                        "required":["name"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws IOException {
        JSONObject args = MemoryArguments.parse(arguments, Set.of("name"));
        String name = MemoryArguments.requiredString(args, "name");
        store.delete(name);
        return "已删除记忆 " + name + "。本次会话的索引不变，下次启动时不再出现。";
    }
}
