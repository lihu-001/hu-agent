package dev.huagent.tool.file;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

public final class EditFileTool implements AgentTool {
    private final WorkspaceFiles files;

    public EditFileTool(WorkspaceFiles files) {
        this.files = files;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("edit_file",
                "精确替换 UTF-8 文件中首次出现的 old_text；最多 1 MiB。old_text 不能为空，new_text 可为空；找不到时不修改。",
                JSON.parseObject("""
                        {"type":"object","properties":{"path":{"type":"string"},"old_text":{"type":"string"},
                        "new_text":{"type":"string"}},"required":["path","old_text","new_text"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws IOException {
        JSONObject args = ToolArguments.parse(arguments, Set.of("path", "old_text", "new_text"));
        String path = ToolArguments.string(args, "path", false);
        String oldText = ToolArguments.string(args, "old_text", true);
        String newText = ToolArguments.string(args, "new_text", true);
        if (oldText.isEmpty()) {
            throw new IllegalArgumentException("old_text 不能为空字符串。");
        }
        Path target = files.resolve(path);
        String content = files.read(target);
        int index = content.indexOf(oldText);
        if (index < 0) {
            throw new IllegalArgumentException("未找到 old_text，文件未修改。");
        }
        files.write(target, content.substring(0, index) + newText + content.substring(index + oldText.length()));
        return "已编辑 " + path + "（替换首次匹配）。";
    }
}
