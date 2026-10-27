package dev.huagent.tool.file;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public final class ReadFileTool implements AgentTool {
    private final WorkspaceFiles files;

    public ReadFileTool(WorkspaceFiles files) {
        this.files = files;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("read_file",
                "读取工作目录内的 UTF-8 文本文件（最多 1 MiB）。可用 limit 限制前几行；最多返回 12000 个内容字符。",
                JSON.parseObject("""
                        {"type":"object","properties":{"path":{"type":"string"},
                        "limit":{"type":"integer","minimum":1}},"required":["path"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws IOException {
        JSONObject args = ToolArguments.parse(arguments, Set.of("path", "limit"));
        String path = ToolArguments.string(args, "path", false);
        int limit = ToolArguments.positiveInt(args, "limit", Integer.MAX_VALUE);
        String content = files.read(files.resolve(path));
        if (content.isEmpty()) {
            return "[空文件]";
        }
        List<String> lines = content.lines().toList();
        String suffix = "";
        if (lines.size() > limit) {
            content = String.join("\n", lines.subList(0, limit));
            suffix = "\n[还有 " + (lines.size() - limit) + " 行未显示]";
        }
        if (content.length() > WorkspaceFiles.MAX_READ_CHARS) {
            content = content.substring(0, WorkspaceFiles.MAX_READ_CHARS);
            suffix += "\n[输出已截断，上限 12000 字符]";
        }
        return content + suffix;
    }
}
