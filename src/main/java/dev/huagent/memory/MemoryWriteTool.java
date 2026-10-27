package dev.huagent.memory;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/** 创建或整体覆盖一条记忆；写入磁盘后下次启动出现在索引中。 */
public final class MemoryWriteTool implements AgentTool {
    public static final String NAME = "memory_write";

    private final MemoryStore store;

    public MemoryWriteTool(MemoryStore store) {
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(NAME,
                "保存一条跨会话记忆，同名时整体覆盖（更新前先用 memory_read 读取旧内容再合并）。"
                        + "只保存以后的会话仍有用、且无法从文件或代码直接得知的信息：用户身份与偏好（user）、"
                        + "用户对做法的纠正或认可及原因（feedback）、项目背景与约束（project）、外部资源位置（reference）。"
                        + "不要保存密钥、一次性的任务进度或可以重新读取的文件内容。",
                JSON.parseObject("""
                        {"type":"object","properties":{
                        "name":{"type":"string","description":"小写字母、数字和连字符，如 prefer-junit5"},
                        "type":{"type":"string","enum":["user","feedback","project","reference"]},
                        "description":{"type":"string","description":"一行摘要，显示在下次会话的记忆索引中"},
                        "content":{"type":"string","description":"记忆正文；feedback 与 project 写明原因和适用方式"}},
                        "required":["name","type","description","content"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws IOException {
        JSONObject args = MemoryArguments.parse(arguments, Set.of("name", "type", "description", "content"));
        String name = MemoryArguments.requiredString(args, "name");
        MemoryType type = MemoryType.parse(MemoryArguments.requiredString(args, "type"));
        boolean created = store.write(name, type, MemoryArguments.requiredString(args, "description"),
                MemoryArguments.requiredString(args, "content"));
        return (created ? "已新建记忆 " : "已覆盖记忆 ") + name
                + "。本次会话的索引不变，下次启动时出现在系统提示词中；本次需要时可用 memory_read 读取。";
    }
}
