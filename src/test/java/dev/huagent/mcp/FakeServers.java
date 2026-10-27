package dev.huagent.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 测试辅助：生成以独立 JVM 运行 FakeMcpServer 的 mcp.json。 */
final class FakeServers {
    private FakeServers() {
    }

    static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    static String classPath() {
        // surefire 的 java.class.path 可能只是引导 jar；测试类路径单独放在这个属性中。
        String surefire = System.getProperty("surefire.test.class.path");
        return surefire != null && !surefire.isBlank() ? surefire : System.getProperty("java.class.path");
    }

    static List<String> args(String mode, Path record) {
        List<String> args = new ArrayList<>(List.of("-cp", classPath(), FakeMcpServer.class.getName(), mode));
        if (record != null) {
            args.add(record.toString());
        }
        return args;
    }

    static McpConfig.Server server(String name, String mode, Path record, int timeoutSeconds) {
        return new McpConfig.Server(name, java(), args(mode, record), Map.of(),
                java.time.Duration.ofSeconds(timeoutSeconds), List.of(), List.of());
    }

    /** servers：名称 → 额外字段（mode、allow、env、timeout）。 */
    static void writeConfig(Path project, Map<String, Map<String, Object>> servers) throws IOException {
        JSONObject all = new JSONObject(new LinkedHashMap<>());
        for (Map.Entry<String, Map<String, Object>> entry : servers.entrySet()) {
            Map<String, Object> options = new LinkedHashMap<>(entry.getValue());
            String mode = (String) options.remove("mode");
            Path record = (Path) options.remove("record");
            JSONObject server = new JSONObject(new LinkedHashMap<>());
            server.put("command", options.containsKey("command") ? options.remove("command") : java());
            server.put("args", JSONArray.from(args(mode == null ? "normal" : mode, record)));
            server.putAll(options);
            all.put(entry.getKey(), server);
        }
        Path file = project.resolve(McpConfig.DEFAULT_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.toJSONString(JSONObject.of("mcpServers", all)));
    }
}
