package dev.huagent.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 测试用的 stdio MCP server，由测试以独立 JVM 启动：java -cp &lt;测试类路径&gt; FakeMcpServer &lt;mode&gt; [记录文件]。
 * 工具：echo（原样返回 text）、add（两数相加）、fail（isError）、slow（不响应）、exit（进程退出）、image（非文本内容）。
 * mode：normal；paged（tools/list 分两页）；crash（握手前退出并写 stderr）；noisy（stdout 混入非 JSON 行，并先向客户端发 ping）。
 */
public final class FakeMcpServer {
    private FakeMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "normal";
        Path record = args.length > 1 ? Path.of(args[1]) : null;
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.err.println("fake server starting in mode " + mode);
        if (mode.equals("crash")) {
            System.err.println("missing API token");
            System.exit(3);
        }
        if (record != null) {
            Files.writeString(record, "started " + System.getenv().getOrDefault("FAKE_TOKEN", "-") + "\n");
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            JSONObject message = JSON.parseObject(line);
            if (record != null) {
                Files.writeString(record, message.getString("method") + "\n", java.nio.file.StandardOpenOption.APPEND);
            }
            if (!message.containsKey("id") || !message.containsKey("method")) {
                continue; // 通知或客户端对 ping 的回复
            }
            Object id = message.get("id");
            JSONObject params = message.getJSONObject("params");
            JSONObject reply = JSONObject.of("jsonrpc", "2.0", "id", id);
            switch (message.getString("method")) {
                case "initialize" -> {
                    if (mode.equals("noisy")) {
                        out.println("this is not json");
                        out.println(JSONObject.of("jsonrpc", "2.0", "id", "srv-1", "method", "ping").toJSONString());
                        out.println(JSONObject.of("jsonrpc", "2.0", "id", "srv-2", "method", "sampling/createMessage")
                                .toJSONString());
                    }
                    reply.put("result", JSONObject.of("protocolVersion", params.getString("protocolVersion"),
                            "capabilities", JSONObject.of("tools", new JSONObject()),
                            "serverInfo", JSONObject.of("name", "fake", "version", "1")));
                }
                case "tools/list" -> {
                    List<JSONObject> all = List.of(
                            tool("echo", "原样返回 text", "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}"),
                            tool("add", "两数相加", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}}}"),
                            tool("fail", "总是失败", "{\"type\":\"object\"}"),
                            tool("slow", "永不返回", "{\"type\":\"object\"}"),
                            tool("exit", "让 server 退出", "{\"type\":\"object\"}"),
                            tool("image", "返回图片", "{\"type\":\"object\"}"));
                    JSONArray tools = new JSONArray();
                    if (mode.equals("paged")) {
                        boolean second = params != null && "page2".equals(params.getString("cursor"));
                        tools.addAll(second ? all.subList(3, all.size()) : all.subList(0, 3));
                        reply.put("result", second ? JSONObject.of("tools", tools)
                                : JSONObject.of("tools", tools, "nextCursor", "page2"));
                    } else {
                        tools.addAll(all);
                        reply.put("result", JSONObject.of("tools", tools));
                    }
                }
                case "tools/call" -> {
                    String name = params.getString("name");
                    JSONObject arguments = params.getJSONObject("arguments");
                    switch (name) {
                        case "echo" -> reply.put("result", text(arguments.getString("text"), false));
                        case "add" -> reply.put("result", text(String.valueOf(
                                arguments.getDoubleValue("a") + arguments.getDoubleValue("b")), false));
                        case "fail" -> reply.put("result", text("磁盘已满", true));
                        case "slow" -> {
                            continue;
                        }
                        case "exit" -> System.exit(0);
                        case "image" -> reply.put("result", JSONObject.of("content", JSONArray.of(
                                JSONObject.of("type", "image", "data", "AAAA", "mimeType", "image/png"),
                                JSONObject.of("type", "text", "text", "说明"))));
                        default -> reply.put("error", JSONObject.of("code", -32602, "message", "Unknown tool: " + name));
                    }
                }
                default -> reply.put("error", JSONObject.of("code", -32601, "message", "Method not found"));
            }
            out.println(reply.toJSONString());
        }
    }

    private static JSONObject tool(String name, String description, String schema) {
        return JSONObject.of("name", name, "description", description, "inputSchema", JSON.parseObject(schema));
    }

    private static JSONObject text(String value, boolean error) {
        return JSONObject.of("content", JSONArray.of(JSONObject.of("type", "text", "text", value)), "isError", error);
    }
}
