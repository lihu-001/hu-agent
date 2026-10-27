package dev.huagent.mcp;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 把一个 MCP 工具包装成普通 AgentTool：名称加 mcp__&lt;server&gt;__ 前缀，参数原样转发，结果只取文本内容。
 * 它与内置工具一样经过 ToolRegistry 的 Hook 和权限检查，模型看不出差别。
 */
public final class McpTool implements AgentTool {
    public static final String PREFIX = "mcp__";
    public static final int MAX_RESULT_CHARS = 12000;
    /** OpenAI 兼容接口对函数名的限制。 */
    private static final Pattern FUNCTION_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_DESCRIPTION_CHARS = 1024;

    private final McpClient client;
    private final McpClient.ToolInfo info;
    private final String name;

    McpTool(McpClient client, McpClient.ToolInfo info) {
        this.client = Objects.requireNonNull(client);
        this.info = Objects.requireNonNull(info);
        this.name = qualifiedName(client.name(), info.name());
        if (!FUNCTION_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("工具名 " + name + " 不符合函数名要求（字母、数字、_、-，最长 64）");
        }
    }

    public static String qualifiedName(String server, String tool) {
        return PREFIX + server + "__" + tool;
    }

    public String server() {
        return client.name();
    }

    public String remoteName() {
        return info.name();
    }

    @Override
    public ToolDefinition definition() {
        String description = info.description().isBlank() ? "（server 未提供描述）" : info.description();
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            description = description.substring(0, MAX_DESCRIPTION_CHARS) + "…";
        }
        // 描述来自外部 server，标明来源，提示词规则说明它不是用户指令。
        return new ToolDefinition(name, "[MCP " + client.name() + "] " + description, info.inputSchema());
    }

    @Override
    public String execute(String arguments) throws IOException, InterruptedException {
        JSONObject args;
        try {
            args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("arguments 必须是有效的 JSON 对象。");
        }
        if (args == null) {
            throw new IllegalArgumentException("arguments 必须是 JSON 对象。");
        }
        // 参数按 inputSchema 的校验交给 server；客户端不实现 JSON Schema。
        return render(client.callTool(info.name(), args));
    }

    /** content 中的文本按顺序拼接；图片、音频等非文本内容只给出占位说明。isError 表示工具执行失败。 */
    static String render(JSONObject result) {
        StringBuilder text = new StringBuilder();
        if (result.get("content") instanceof JSONArray content) {
            for (Object item : content) {
                if (!(item instanceof JSONObject block)) {
                    continue;
                }
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                if ("text".equals(block.get("type")) && block.get("text") instanceof String value) {
                    text.append(value);
                } else if ("resource".equals(block.get("type")) && block.get("resource") instanceof JSONObject resource
                        && resource.get("text") instanceof String value) {
                    text.append(value);
                } else {
                    text.append("[非文本内容：").append(block.get("type")).append("，未展示]");
                }
            }
        }
        if (text.isEmpty() && result.get("structuredContent") instanceof JSONObject structured) {
            text.append(structured.toJSONString());
        }
        String body = text.isEmpty() ? "（无文本内容）" : text.toString();
        if (body.length() > MAX_RESULT_CHARS) {
            body = body.substring(0, MAX_RESULT_CHARS) + "\n[输出已截断，上限 " + MAX_RESULT_CHARS + " 字符]";
        }
        return Boolean.TRUE.equals(result.get("isError")) ? "工具错误：" + body : body;
    }
}
