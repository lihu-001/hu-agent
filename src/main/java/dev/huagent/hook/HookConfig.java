package dev.huagent.hook;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 启动时读取一次配置；先完整校验，再返回回调，加载本身不会执行命令。 */
public final class HookConfig {
    public static final Path DEFAULT_PATH = Path.of(".agents", "hook.json");
    private static final Set<String> EVENTS =
            Set.of("UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop");

    private HookConfig() {
    }

    /** 包内协议测试入口；应用启动必须通过 HookTrust 加载并授权。 */
    static List<AgentHook> load(Path projectDirectory, Consumer<String> output) throws IOException {
        return read(projectDirectory).callbacks(output, null);
    }

    record Definition(String event, String matcher, String command, Duration timeout, List<String> trustFiles) {
    }

    record Configuration(Path directory, String source, List<Definition> definitions) {
        List<AgentHook> callbacks(Consumer<String> output, HookTrustSnapshot snapshot) {
            return definitions.stream().<AgentHook>map(definition -> new CommandHook(
                    definition.event(), Pattern.compile(definition.matcher().equals("*")
                            ? ".*" : definition.matcher()), definition.command(), directory,
                    definition.timeout(), output, snapshot)).toList();
        }
    }

    static Configuration read(Path projectDirectory) throws IOException {
        Path cwd = projectDirectory.toAbsolutePath().normalize();
        Path file = cwd.resolve(DEFAULT_PATH);
        String text;
        try {
            text = Files.readString(file);
        } catch (NoSuchFileException e) {
            return new Configuration(cwd, "", List.of());
        } catch (IOException e) {
            throw new IOException("无法读取 Hook 配置：" + file, e);
        }
        String source = text;
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        try {
            JSONObject root = object(JSON.parse(text), "根节点");
            keys(root, Set.of("hooks"), "根节点");
            JSONObject events = object(root.get("hooks"), "hooks");
            keys(events, EVENTS, "hooks");
            List<Definition> definitions = new ArrayList<>();
            for (String event : events.keySet()) {
                for (Object entry : array(events.get(event), event)) {
                    JSONObject group = object(entry, event + " 分组");
                    keys(group, Set.of("matcher", "hooks"), event + " 分组");
                    boolean toolEvent = event.equals("PreToolUse") || event.equals("PostToolUse");
                    if (!toolEvent && group.containsKey("matcher")) {
                        throw invalid(event + " 不支持 matcher");
                    }
                    String matcher = group.containsKey("matcher")
                            ? string(group.get("matcher"), "matcher") : "*";
                    try {
                        Pattern.compile(matcher.equals("*") ? ".*" : matcher);
                    } catch (PatternSyntaxException e) {
                        throw invalid(event + " matcher 不是有效正则表达式");
                    }
                    for (Object value : array(group.get("hooks"), event + ".hooks")) {
                        JSONObject command = object(value, "命令");
                        keys(command, Set.of("type", "command", "timeout", "trust_files"), "命令");
                        if (!"command".equals(command.get("type"))) {
                            throw invalid("type 必须为 command");
                        }
                        String script = string(command.get("command"), "command");
                        int timeout = 30;
                        if (command.containsKey("timeout")) {
                            Object seconds = command.get("timeout");
                            if (!(seconds instanceof Integer number) || number < 1 || number > 300) {
                                throw invalid("timeout 必须为 1—300 的整数秒");
                            }
                            timeout = number;
                        }
                        List<String> trustFiles = new ArrayList<>();
                        if (command.containsKey("trust_files")) {
                            for (Object path : array(command.get("trust_files"), "trust_files")) {
                                trustFiles.add(string(path, "trust_files 路径"));
                            }
                        }
                        definitions.add(new Definition(event, matcher, script,
                                Duration.ofSeconds(timeout), List.copyOf(trustFiles)));
                    }
                }
            }
            return new Configuration(cwd, source, List.copyOf(definitions));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Hook 配置 " + file + "：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            // 不回显原始配置，避免异常消息暴露命令中的私有参数。
            throw new IllegalArgumentException("Hook 配置 " + file + "：JSON 格式无效", e);
        }
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
            throw invalid(field + " 含不支持的字段或事件");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
