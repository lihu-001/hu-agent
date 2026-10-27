package dev.huagent.tool.file;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.util.Set;

public final class WriteFileTool implements AgentTool {
    private final WorkspaceFiles files;

    public WriteFileTool(WorkspaceFiles files) {
        this.files = files;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("write_file",
                "以 UTF-8 写入工作目录内的文件，覆盖已有内容，自动创建父目录。允许空内容，最多 1 MiB。",
                JSON.parseObject("""
                        {"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},
                        "required":["path","content"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws IOException {
        JSONObject args = ToolArguments.parse(arguments, Set.of("path", "content"));
        String path = ToolArguments.string(args, "path", false);
        String content = ToolArguments.string(args, "content", true);
        int bytes = files.write(files.resolve(path), content);
        return "已写入 " + path + "（" + bytes + " 字节，UTF-8）。";
    }
}
