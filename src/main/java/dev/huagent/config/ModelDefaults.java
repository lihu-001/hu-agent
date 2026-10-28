package dev.huagent.config;

import dev.huagent.io.AtomicFiles;

import cn.hutool.core.util.StrUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /model 切换后把模型写回启动时读取它的配置文件，下次启动沿用：有 model.properties 时写它的 model，
 * 否则写 config.properties 里旧版的 openai.model（与 AgentConfig.load 的选择相同）。
 * 只改这一项，注释、其他配置、BOM 与换行符原样保留。环境变量 HU_MODEL 优先于文件，此时不写。
 */
public final class ModelDefaults {
    /** Windows 编辑器保存的 UTF-8 BOM（见 PropertiesFile.read），写回时保留。 */
    private static final String BOM = String.valueOf((char) 0xFEFF);

    private final Path file;
    private final String key;

    ModelDefaults(Path file, String key) {
        this.file = file;
        this.key = key;
    }

    public static ModelDefaults locate(Path configFile, Path modelFile, Map<String, String> environment) {
        if (StrUtil.isNotBlank(environment.get("HU_MODEL"))) {
            return new ModelDefaults(null, null);
        }
        return Files.exists(modelFile) ? new ModelDefaults(modelFile, "model")
                : new ModelDefaults(configFile, "openai.model");
    }

    /** 写入的文件；为 null 时模型由环境变量 HU_MODEL 决定，不能保存。 */
    public Path file() {
        return file;
    }

    /** 临时文件写好后原子替换；配置文件是符号链接时替换它指向的文件，链接本身保留。 */
    public void save(String model) throws IOException {
        if (file == null) {
            throw new IllegalStateException("模型由环境变量 HU_MODEL 决定，不写配置文件");
        }
        Path target = file.toRealPath();
        String text = replace(Files.readString(target, StandardCharsets.UTF_8), key, ModelConfig.checkedModel(model));
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            AtomicFiles.replace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 把 key 的每一个配置行（连同反斜杠续行）改成 key=value，保留行首缩进；没有这一项时追加到末尾。
     * 注释行与其他配置原样保留，换行符沿用文件原来的写法。
     */
    static String replace(String text, String key, String value) {
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        String bom = text.startsWith(BOM) ? BOM : "";
        List<String> lines = List.of(text.substring(bom.length()).split("\r?\n", -1));
        List<String> result = new ArrayList<>();
        String entry = key + "=" + value.replace("\\", "\\\\");
        boolean continued = false;
        boolean dropping = false;
        boolean replaced = false;
        for (String line : lines) {
            if (continued) {
                // 上一行以奇数个反斜杠结尾，这一行属于同一配置项。
                continued = continues(line);
                if (!dropping) {
                    result.add(line);
                }
                continue;
            }
            String content = line.stripLeading();
            if (content.isEmpty() || content.startsWith("#") || content.startsWith("!")) {
                // 注释行不能续行。
                result.add(line);
                continue;
            }
            continued = continues(line);
            dropping = key.equals(keyOf(content));
            if (dropping) {
                result.add(line.substring(0, line.length() - content.length()) + entry);
                replaced = true;
            } else {
                result.add(line);
            }
        }
        if (!replaced) {
            // 文件以换行结尾时 split 的最后一项是空串，新行插在它前面，结尾的换行保留。
            int end = result.getLast().isEmpty() ? result.size() - 1 : result.size();
            result.add(end, entry);
        }
        return bom + String.join(newline, result);
    }

    /** properties 的键在第一个未转义的 =、: 或空白处结束。 */
    private static String keyOf(String line) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                key.append(line.charAt(++i));
            } else if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                break;
            } else {
                key.append(c);
            }
        }
        return key.toString();
    }

    private static boolean continues(String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }
}
