package dev.huagent.tool.file;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 与 Claude Code 的 Edit 相同：old_text 在文件中必须唯一，否则不修改并报告出现次数，避免改错位置；
 * replace_all 为 true 时替换全部出现。
 */
public final class EditFileTool implements AgentTool {
    /** read_file 的行号前缀（空格、行号、制表符）；模型把它抄进 old_text 时提示去掉。 */
    private static final Pattern LINE_NUMBER_PREFIX = Pattern.compile("(?m)^ *\\d+\\t");
    private final WorkspaceFiles files;

    public EditFileTool(WorkspaceFiles files) {
        this.files = files;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("edit_file",
                "精确替换 UTF-8 文件中的 old_text；最多 1 MiB。old_text 在文件中必须唯一（不唯一时带上更多上下文），"
                        + "要替换全部出现时设 replace_all 为 true。old_text 不能为空，new_text 可为空；"
                        + "old_text 取自 read_file 结果时去掉行号前缀。找不到或不唯一时不修改。"
                        + "例：read_file 显示“    42\tint count = 0;”时，"
                        + "写 {\"path\":\"src/App.java\",\"old_text\":\"int count = 0;\",\"new_text\":\"int count = 1;\"}。",
                JSON.parseObject("""
                        {"type":"object","properties":{"path":{"type":"string"},"old_text":{"type":"string"},
                        "new_text":{"type":"string"},"replace_all":{"type":"boolean"}},
                        "required":["path","old_text","new_text"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws IOException {
        JSONObject args = ToolArguments.parse(arguments, Set.of("path", "old_text", "new_text", "replace_all"));
        String path = ToolArguments.string(args, "path", false);
        String oldText = ToolArguments.string(args, "old_text", true);
        String newText = ToolArguments.string(args, "new_text", true);
        boolean replaceAll = ToolArguments.bool(args, "replace_all", false);
        if (oldText.isEmpty()) {
            throw new IllegalArgumentException("old_text 不能为空字符串。");
        }
        Path target = files.resolve(path);
        String content = files.read(target);
        int index = content.indexOf(oldText);
        if (index < 0) {
            throw new IllegalArgumentException("未找到 old_text，文件未修改。"
                    + (LINE_NUMBER_PREFIX.matcher(oldText).find()
                    ? "old_text 里似乎带着 read_file 的行号前缀，去掉后重试。" : ""));
        }
        int count = 0;
        for (int at = index; at >= 0; at = content.indexOf(oldText, at + oldText.length())) {
            count++;
        }
        if (count > 1 && !replaceAll) {
            throw new IllegalArgumentException("old_text 在文件中出现了 " + count
                    + " 次，文件未修改。带上更多上下文使它唯一，或设 replace_all 为 true 替换全部。");
        }
        files.write(target, replaceAll ? content.replace(oldText, newText)
                : content.substring(0, index) + newText + content.substring(index + oldText.length()));
        return "已编辑 " + path + (count > 1 ? "（替换了 " + count + " 处）。" : "。");
    }
}
