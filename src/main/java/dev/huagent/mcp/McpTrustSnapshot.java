package dev.huagent.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户批准的内容快照：mcp.json 原文、args 中可识别的字面量脚本，以及 trust_files 声明的文件。
 * 与 S04 的 HookTrustSnapshot 思路相同，但 server 只在启动时运行一次命令，所以只在确认后、启动前再校验一次。
 */
final class McpTrustSnapshot {
    private static final Pattern SCRIPT = Pattern.compile("(?i).*\\.(py|ps1|sh|bash|js|mjs|cjs|ts|rb|pl|bat|cmd)$");
    private final Path project;
    private final Path config;
    private final String source;
    private final List<Path> files;
    private final String fingerprint;

    private McpTrustSnapshot(McpConfig.Configuration configuration) throws IOException {
        project = configuration.directory().toRealPath();
        config = project.resolve(McpConfig.DEFAULT_PATH);
        source = configuration.source();
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        for (McpConfig.Server server : configuration.servers()) {
            for (String path : server.trustFiles()) {
                paths.add(project.resolve(path).normalize());
            }
            // 只识别字面量脚本路径；npx/uvx 下载的包、动态加载的代码不在快照中，需要用户自行判断来源。
            for (String argument : server.args()) {
                if (SCRIPT.matcher(argument).matches() && !argument.startsWith("-")
                        && !argument.contains("$") && !argument.contains("%")) {
                    paths.add(project.resolve(argument).normalize());
                }
            }
        }
        files = List.copyOf(paths);
        fingerprint = currentFingerprint();
    }

    static McpTrustSnapshot capture(McpConfig.Configuration configuration) throws IOException {
        return new McpTrustSnapshot(configuration);
    }

    Path project() {
        return project;
    }

    List<Path> files() {
        return files;
    }

    String fingerprint() {
        return fingerprint;
    }

    /** 不允许确认过程中替换配置或脚本后使用刚才的授权。 */
    void verify() throws IOException {
        if (!fingerprint.equals(currentFingerprint())) {
            throw new IOException("MCP 配置或脚本发生变化");
        }
    }

    private String currentFingerprint() throws IOException {
        if (!Files.readString(config).equals(source)) {
            throw new IOException("MCP 配置在加载后发生变化");
        }
        MessageDigest digest = digest();
        add(digest, "java-agent-mcp-trust-v1");
        add(digest, project.toString());
        add(digest, source);
        for (Path file : files) {
            add(digest, file.toString());
            add(digest, file.toRealPath().toString());
            add(digest, fileHash(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String hash(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String fileHash(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("信任校验目标不是普通文件：" + file);
        }
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
