package dev.huagent.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 读取 S15 子项目的 .agents/mcp.json，先完整校验，加载本身不启动任何进程。
 * 格式与 Claude Code 的 mcpServers 相近，只支持 stdio：command 是可执行文件，args 原样作为参数列表，不经过 Shell。
 */
public final class McpConfig {
    public static final Path DEFAULT_PATH = Path.of(".agents", "mcp.json");
    public static final int MAX_SERVERS = 8;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    /** 名称不含下划线，保证 mcp__<server>__<tool> 能无歧义地拆回 server 与工具。 */
    private static final Pattern SERVER_NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Set<String> SERVER_KEYS = Set.of("command", "args", "env", "timeout", "allow", "trust_files");

    public record Server(String name, String command, List<String> args, Map<String, String> env,
                         Duration timeout, List<String> allow, List<String> trustFiles) {
    }

    public record Configuration(Path directory, String source, List<Server> servers) {
    }

    private McpConfig() {
    }

    public static Configuration read(Path projectDirectory) throws IOException {
        Path cwd = projectDirectory.toAbsolutePath().normalize();
        Path file = cwd.resolve(DEFAULT_PATH);
        String text;
        try {
            text = Files.readString(file);
        } catch (NoSuchFileException e) {
            return new Configuration(cwd, "", List.of());
        } catch (IOException e) {
            throw new IOException("无法读取 MCP 配置：" + file, e);
        }
        String source = text;
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        try {
            JSONObject root = object(JSON.parse(text), "根节点");
            keys(root, Set.of("mcpServers"), "根节点");
            JSONObject servers = object(root.get("mcpServers"), "mcpServers");
            if (servers.size() > MAX_SERVERS) {
                throw invalid("最多配置 " + MAX_SERVERS + " 个 server");
            }
            List<Server> result = new ArrayList<>();
            for (String name : servers.keySet()) {
                if (!SERVER_NAME.matcher(name).matches()) {
                    throw invalid("server 名称 " + name + " 只能是小写字母开头的小写字母、数字和连字符，最长 32");
                }
                JSONObject server = object(servers.get(name), name);
                keys(server, SERVER_KEYS, name);
                int timeout = DEFAULT_TIMEOUT_SECONDS;
                if (server.containsKey("timeout")) {
                    if (!(server.get("timeout") instanceof Integer seconds) || seconds < 1 || seconds > 300) {
                        throw invalid(name + ".timeout 必须为 1—300 的整数秒");
                    }
                    timeout = seconds;
                }
                Map<String, String> env = new LinkedHashMap<>();
                if (server.containsKey("env")) {
                    JSONObject values = object(server.get("env"), name + ".env");
                    for (String key : values.keySet()) {
                        if (key.isBlank() || key.contains("=")) {
                            throw invalid(name + ".env 变量名无效");
                        }
                        if (!(values.get(key) instanceof String value)) {
                            throw invalid(name + ".env." + key + " 必须为字符串");
                        }
                        env.put(key, value);
                    }
                }
                result.add(new Server(name, string(server.get("command"), name + ".command"),
                        strings(server, "args", name, true), Map.copyOf(env), Duration.ofSeconds(timeout),
                        strings(server, "allow", name, false), strings(server, "trust_files", name, false)));
            }
            return new Configuration(cwd, source, List.copyOf(result));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MCP 配置 " + file + "：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            // 不回显原始配置，env 中可能有令牌。
            throw new IllegalArgumentException("MCP 配置 " + file + "：JSON 格式无效", e);
        }
    }

    private static List<String> strings(JSONObject server, String key, String name, boolean allowEmpty) {
        if (!server.containsKey(key)) {
            return List.of();
        }
        JSONArray array = array(server.get(key), name + "." + key);
        List<String> values = new ArrayList<>();
        for (Object value : array) {
            if (!(value instanceof String text) || (!allowEmpty && text.isBlank())) {
                throw invalid(name + "." + key + " 只能包含" + (allowEmpty ? "" : "非空") + "字符串");
            }
            values.add(text);
        }
        return List.copyOf(values);
    }

    private static JSONObject object(Object value, String field) {
        if (!(value instanceof JSONObject object)) {
            throw invalid(field + " 必须为对象");
        }
        return object;
    }

    private static JSONArray array(Object value, String field) {
        if (!(value instanceof JSONArray array)) {
            throw invalid(field + " 必须为数组");
        }
        return array;
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String string) || string.isBlank()) {
            throw invalid(field + " 必须为非空字符串");
        }
        return string;
    }

    private static void keys(JSONObject object, Set<String> allowed, String field) {
        if (!allowed.containsAll(object.keySet())) {
            throw invalid(field + " 含不支持的字段");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
