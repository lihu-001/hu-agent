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
 * 超过字符上限时按整行截断，并说明从哪一行继续。单行超过 MAX_LINE_CHARS 时截断，并说明用 column 从哪里接着读这一行。
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
                        + "单行超过 " + MAX_LINE_CHARS + " 个字符时截断，用 column（offset 那一行从第几个字符开始，"
                        + "从 1 开始）接着读这一行。行号只用于定位，edit_file 的 old_text 要用不带行号前缀的原文。"
                        + "例：{\"path\":\"src/App.java\",\"offset\":120,\"limit\":80} 读第 120–199 行。",
                JSON.parseObject("""
                        {"type":"object","properties":{"path":{"type":"string"},
                        "offset":{"type":"integer","minimum":1},"limit":{"type":"integer","minimum":1},
                        "column":{"type":"integer","minimum":1,"description":"只作用于 offset 那一行：从第几个字符开始显示，用于接着读被截断的长行"}},
                        "required":["path"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws IOException {
        JSONObject args = ToolArguments.parse(arguments, Set.of("path", "offset", "limit", "column"));
        String path = ToolArguments.string(args, "path", false);
        int offset = ToolArguments.positiveInt(args, "offset", 1);
        int limit = ToolArguments.positiveInt(args, "limit", DEFAULT_LIMIT);
        int column = ToolArguments.positiveInt(args, "column", 1);
        String content = files.read(files.resolve(path));
        if (content.isEmpty()) {
            return "[空文件]";
        }
        List<String> lines = content.lines().toList();
        if (offset > lines.size()) {
            throw new IllegalArgumentException("offset 超出范围：文件共 " + lines.size() + " 行。");
        }
        int first = lines.get(offset - 1).length();
        if (column > 1 && column > first) {
            throw new IllegalArgumentException("column 超出范围：第 " + offset + " 行共 " + first + " 个字符。");
        }
        int end = (int) Math.min(lines.size(), (long) offset - 1 + limit);
        StringBuilder text = new StringBuilder();
        int next = offset - 1;
        for (; next < end; next++) {
            String line = String.format("%6d\t%s", next + 1,
                    clip(lines.get(next), next + 1, next == offset - 1 ? column : 1));
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

    /** 第 number 行从第 column 个字符起显示，最多 MAX_LINE_CHARS 个；没显示完整时说明显示的范围和接着读的参数。 */
    private static String clip(String line, int number, int column) {
        int from = column - 1;
        int to = Math.min(line.length(), from + MAX_LINE_CHARS);
        if (from == 0 && to == line.length()) {
            return line;
        }
        StringBuilder text = new StringBuilder(line.substring(from, to));
        text.append(to < line.length() ? "…" : "").append("（本行共 ").append(line.length()).append(" 字符");
        if (from > 0) {
            text.append("，以上是第 ").append(from + 1).append("–").append(to).append(" 个");
        }
        if (to < line.length()) {
            text.append("，已截断；用 offset=").append(number).append("、column=").append(to + 1).append(" 接着读本行");
        }
        return text.append("）").toString();
    }
}
