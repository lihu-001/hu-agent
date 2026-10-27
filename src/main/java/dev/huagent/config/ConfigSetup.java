package dev.huagent.config;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** 首次运行时在终端询问接口地址、模型与 api-key，按配置模板写入 config.properties；其余设置保持模板默认值。 */
public final class ConfigSetup {
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String TEMPLATE = "/hu-agent/config.example.properties";

    private ConfigSetup() {
    }

    /**
     * 返回 false 表示输入在填写完成前结束（EOF），此时不写文件。
     * console 非 null 时 api-key 用 readPassword 读取，输入不回显。
     */
    public static boolean run(BufferedReader input, PrintWriter output, Console console, Path file) throws IOException {
        output.println("hu> 首次运行，需要配置模型接口（兼容 OpenAI Chat Completions 协议）。");
        output.println("hu> 配置将保存到 " + file + "，之后可以直接编辑。");
        String baseUrl = ask(input, output, "接口地址 [" + DEFAULT_BASE_URL + "]：");
        if (baseUrl == null) {
            return false;
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
        write(file, render(template(), baseUrl.isEmpty() ? DEFAULT_BASE_URL : baseUrl, model, apiKey));
        output.println("hu> 已保存配置：" + file);
        return true;
    }

    /** 只替换模板中三行必填项，注释与其他默认值原样保留。 */
    static String render(String template, String baseUrl, String model, String apiKey) {
        StringBuilder text = new StringBuilder();
        for (String line : template.split("\n", -1)) {
            String value = line.startsWith("openai.base-url=") ? baseUrl
                    : line.startsWith("openai.model=") ? model
                    : line.startsWith("openai.api-key=") ? apiKey : null;
            text.append(value == null ? line : line.substring(0, line.indexOf('=') + 1) + escape(value)).append('\n');
        }
        return text.substring(0, text.length() - 1);
    }

    static String template() throws IOException {
        try (InputStream stream = ConfigSetup.class.getResourceAsStream(TEMPLATE)) {
            if (stream == null) {
                throw new IOException("缺少配置模板 " + TEMPLATE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
