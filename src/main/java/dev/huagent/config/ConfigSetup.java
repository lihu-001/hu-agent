package dev.huagent.config;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 首次运行时在终端选择接口类型（OpenAI / Anthropic，官方或自定义地址），再填写模型与 api-key，按模板写入
 * model.properties；config.properties 不存在时按模板一并生成，其余设置保持模板默认值。
 */
public final class ConfigSetup {
    private static final String CONFIG_TEMPLATE = "/hu-agent/config.example.properties";

    private ConfigSetup() {
    }

    /**
     * 返回 false 表示输入在填写完成前结束（EOF），此时不写文件。
     * console 非 null 时 api-key 用 readPassword 读取，输入不回显。
     */
    public static boolean run(BufferedReader input, PrintWriter output, Console console, Path modelFile,
                              Path configFile) throws IOException {
        output.println("hu> 首次运行，需要配置模型接口。");
        output.println("hu> 配置将保存到 " + modelFile + "，之后可以直接编辑；删除它再运行 hu 会重新引导。");
        ModelProvider[] providers = ModelProvider.values();
        for (int i = 0; i < providers.length; i++) {
            output.println("  " + (i + 1) + ") " + describe(providers[i]));
        }
        ModelProvider provider = askProvider(input, output, providers);
        if (provider == null) {
            return false;
        }
        String baseUrl = "";
        if (!provider.official()) {
            baseUrl = askUrl(input, output, provider);
            if (baseUrl == null) {
                return false;
            }
        }
        String model = askRequired(input, output, "模型名称：");
        if (model == null) {
            return false;
        }
        String apiKey = console == null ? askRequired(input, output, "API Key：")
                : askSecret(console, output, "API Key（输入不显示）：");
        if (apiKey == null) {
            return false;
        }
        write(modelFile, render(template(ModelConfig.TEMPLATE), provider, baseUrl, model, apiKey));
        if (Files.notExists(configFile)) {
            write(configFile, template(CONFIG_TEMPLATE));
        }
        output.println("hu> 已保存模型配置：" + modelFile);
        return true;
    }

    /** 只替换模板中四行必填项，注释与其他默认值原样保留。 */
    static String render(String template, ModelProvider provider, String baseUrl, String model, String apiKey) {
        StringBuilder text = new StringBuilder();
        for (String line : template.split("\n", -1)) {
            String value = line.startsWith("provider=") ? provider.id()
                    : line.startsWith("base-url=") ? baseUrl
                    : line.startsWith("model=") ? model
                    : line.startsWith("api-key=") ? apiKey : null;
            text.append(value == null ? line : line.substring(0, line.indexOf('=') + 1) + escape(value)).append('\n');
        }
        return text.substring(0, text.length() - 1);
    }

    static String template(String resource) throws IOException {
        try (InputStream stream = ConfigSetup.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("缺少配置模板 " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static String describe(ModelProvider provider) {
        String protocol = provider.protocol() == ModelProvider.Protocol.OPENAI ? "Chat Completions" : "Messages";
        return provider.label() + "（" + (provider.official() ? provider.officialBaseUrl()
                : protocol + " 协议，需填写接口地址") + "）";
    }

    /** 接受序号或配置文件里的写法（如 anthropic）。 */
    private static ModelProvider askProvider(BufferedReader input, PrintWriter output, ModelProvider[] providers)
            throws IOException {
        while (true) {
            String answer = ask(input, output, "选择接口类型 [1-" + providers.length + "]：");
            if (answer == null) {
                return null;
            }
            for (int i = 0; i < providers.length; i++) {
                if (answer.equals(String.valueOf(i + 1)) || answer.equalsIgnoreCase(providers[i].id())) {
                    return providers[i];
                }
            }
            output.println("hu> 请输入 1—" + providers.length + " 之间的序号。");
        }
    }

    private static String askUrl(BufferedReader input, PrintWriter output, ModelProvider provider) throws IOException {
        String prompt = provider.protocol() == ModelProvider.Protocol.OPENAI
                ? "接口地址（通常以 /v1 结尾，请求发往 <地址>/chat/completions）："
                : "接口地址（请求发往 <地址>/v1/messages）：";
        while (true) {
            String value = askRequired(input, output, prompt);
            if (value == null || httpUrl(value)) {
                return value;
            }
            output.println("hu> 请填写以 http:// 或 https:// 开头的地址。");
        }
    }

    private static boolean httpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** POSIX 系统上先以 600 权限创建文件再写入，api-key 不会短暂对其他用户可读。 */
    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        if (Files.notExists(file) && file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** properties 中反斜杠是转义符；readLine 读到的值不含换行。 */
    private static String escape(String value) {
        return value.replace("\\", "\\\\");
    }

    private static String askRequired(BufferedReader input, PrintWriter output, String prompt) throws IOException {
        while (true) {
            String value = ask(input, output, prompt);
            if (value == null || !value.isEmpty()) {
                return value;
            }
            output.println("hu> 不能为空。");
        }
    }

    private static String askSecret(Console console, PrintWriter output, String prompt) {
        while (true) {
            char[] secret = console.readPassword(prompt);
            if (secret == null) {
                return null;
            }
            String value = new String(secret).strip();
            if (!value.isEmpty()) {
                return value;
            }
            output.println("hu> 不能为空。");
        }
    }

    private static String ask(BufferedReader input, PrintWriter output, String prompt) throws IOException {
        output.print(prompt);
        output.flush();
        String line = input.readLine();
        return line == null ? null : line.strip();
    }
}
