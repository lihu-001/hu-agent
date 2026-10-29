package dev.huagent.tool.file;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 与 Claude Code 的 Read 相同：每行带行号（cat -n 格式），默认读前 2000 行，长文件用 offset 与 limit 分段读；
 * 超过字符上限时按整行截断，并说明从哪一行继续。
 */
public final class ReadFileTool implements AgentTool {
    public static final int DEFAULT_LIMIT = 2000;
    static final int MAX_LINE_CHARS = 2000;
    private final WorkspaceFiles files;

    public ReadFileTool(WorkspaceFiles files) {
        this.files = files;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("read_file",
                "读取工作目录内的 UTF-8 文本文件（最多 1 MiB），每行前面是行号和制表符。默认从第 1 行读 "
                        + DEFAULT_LIMIT + " 行；长文件用 offset（起始行号，从 1 开始）和 limit（行数）分段读取。"
                        + "单次最多返回约 " + WorkspaceFiles.MAX_READ_CHARS + " 个字符，超过时按整行截断并说明从哪一行继续。"
                        + "行号只用于定位，edit_file 的 old_text 要用不带行号前缀的原文。"
                        + "例：{\"path\":\"src/App.java\",\"offset\":120,\"limit\":80} 读第 120–199 行。",
                JSON.parseObject("""
                        {"type":"object","properties":{"path":{"type":"string"},
                        "offset":{"type":"integer","minimum":1},"limit":{"type":"integer","minimum":1}},
                        "required":["path"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws IOException {
        JSONObject args = ToolArguments.parse(arguments, Set.of("path", "offset", "limit"));
        String path = ToolArguments.string(args, "path", false);
        int offset = ToolArguments.positiveInt(args, "offset", 1);
        int limit = ToolArguments.positiveInt(args, "limit", DEFAULT_LIMIT);
        String content = files.read(files.resolve(path));
        if (content.isEmpty()) {
            return "[空文件]";
        }
        List<String> lines = content.lines().toList();
        if (offset > lines.size()) {
            throw new IllegalArgumentException("offset 超出范围：文件共 " + lines.size() + " 行。");
        }
        int end = (int) Math.min(lines.size(), (long) offset - 1 + limit);
        StringBuilder text = new StringBuilder();
        int next = offset - 1;
        for (; next < end; next++) {
            String line = String.format("%6d\t%s", next + 1, clip(lines.get(next)));
            // 至少返回一行，之后按整行截断，模型可以从下一行接着读。
            if (next > offset - 1 && text.length() + 1 + line.length() > WorkspaceFiles.MAX_READ_CHARS) {
                break;
            }
            text.append(next > offset - 1 ? "\n" : "").append(line);
        }
        if (next < lines.size()) {
            text.append("\n[已显示第 ").append(offset).append("–").append(next).append(" 行，共 ")
                    .append(lines.size()).append(" 行；用 offset=").append(next + 1).append(" 继续读取]");
        }
        return text.toString();
    }

    private static String clip(String line) {
        return line.length() <= MAX_LINE_CHARS ? line
                : line.substring(0, MAX_LINE_CHARS) + "…（本行共 " + line.length() + " 字符，已截断）";
    }
}
