package dev.huagent.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class McpToolTest {
    private static final McpClient CLIENT = new McpClient(new McpConfig.Server("docs", "x", List.of(), Map.of(),
            java.time.Duration.ofSeconds(1), List.of(), List.of()), (Consumer<String>) line -> { });

    @Test
    void definitionPrefixesNameAndMarksSource() {
        McpTool tool = new McpTool(CLIENT, new McpClient.ToolInfo("search", "查文档", JSON.parseObject("{\"type\":\"object\"}")));
        assertEquals("mcp__docs__search", tool.definition().name());
        assertEquals("[MCP docs] 查文档", tool.definition().description());
        assertEquals("object", tool.definition().parameters().getString("type"));
        assertEquals("[MCP docs] （server 未提供描述）",
                new McpTool(CLIENT, new McpClient.ToolInfo("a", "", new JSONObject())).definition().description());
    }

    @Test
    void longDescriptionsAreTruncated() {
        McpTool tool = new McpTool(CLIENT, new McpClient.ToolInfo("a", "x".repeat(5000), new JSONObject()));
        assertTrue(tool.definition().description().length() < 1100);
    }

    @Test
    void invalidFunctionNamesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpTool(CLIENT, new McpClient.ToolInfo("has space", "", new JSONObject())));
        assertThrows(IllegalArgumentException.class,
                () -> new McpTool(CLIENT, new McpClient.ToolInfo("a".repeat(60), "", new JSONObject())));
    }

    @Test
    void argumentsMustBeJsonObject() {
        McpTool tool = new McpTool(CLIENT, new McpClient.ToolInfo("a", "", new JSONObject()));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("not json"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("[1]"));
    }

    @Test
    void renderKeepsTextAndMarksOtherContent() {
        assertEquals("a\n[非文本内容：image，未展示]\nb", McpTool.render(JSON.parseObject("""
                {"content":[{"type":"text","text":"a"},{"type":"image","data":"x"},{"type":"text","text":"b"}]}""")));
        assertEquals("资源正文", McpTool.render(JSON.parseObject("""
                {"content":[{"type":"resource","resource":{"uri":"file:///a","text":"资源正文"}}]}""")));
        assertEquals("{\"n\":1}", McpTool.render(JSON.parseObject("{\"content\":[],\"structuredContent\":{\"n\":1}}")));
        assertEquals("（无文本内容）", McpTool.render(new JSONObject()));
        assertEquals("工具错误：坏了", McpTool.render(JSON.parseObject(
                "{\"content\":[{\"type\":\"text\",\"text\":\"坏了\"}],\"isError\":true}")));
        String longText = McpTool.render(JSONObject.of("content", com.alibaba.fastjson2.JSONArray.of(JSONObject.of("type", "text",
                "text", "y".repeat(McpTool.MAX_RESULT_CHARS + 10)))));
        assertTrue(longText.endsWith("[输出已截断，上限 " + McpTool.MAX_RESULT_CHARS + " 字符]"));
    }
}
