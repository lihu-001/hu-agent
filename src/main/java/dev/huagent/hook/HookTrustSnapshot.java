package dev.huagent.hook;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 批准的是配置与已识别文件的内容快照，不是对任意 Shell 程序的安全证明。 */
final class HookTrustSnapshot {
    private static final Pattern TOKENS = Pattern.compile("\"([^\"]+)\"|'([^']+)'|([^\\s\"';&|<>]+)");
    private static final Pattern SCRIPT = Pattern.compile("(?i).*\\.(py|ps1|sh|bash|js|mjs|cjs|ts|rb|pl|bat|cmd)$");
    private final Path project;
    private final Path config;
    private final String source;
    private final List<Path> files;
    private final String fingerprint;
    private boolean invalidated;

    private HookTrustSnapshot(HookConfig.Configuration configuration) throws IOException {
        project = configuration.directory().toRealPath();
        config = project.resolve(HookConfig.DEFAULT_PATH);
        source = configuration.source();
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        for (HookConfig.Definition definition : configuration.definitions()) {
            for (String path : definition.trustFiles()) {
                paths.add(project.resolve(path).normalize());
            }
            Matcher tokens = TOKENS.matcher(definition.command());
            while (tokens.find()) {
                String token = tokens.group(1) != null ? tokens.group(1)
                        : tokens.group(2) != null ? tokens.group(2) : tokens.group(3);
                // 只识别字面量脚本路径；变量、动态 import 和解释器内部代码需显式声明依赖。
                if (SCRIPT.matcher(token).matches() && !token.startsWith("-")
                        && !token.contains("$") && !token.contains("%") && !token.contains("`")) {
                    paths.add(project.resolve(token).normalize());
                }
            }
        }
        files = List.copyOf(paths);
        fingerprint = currentFingerprint();
    }

    static HookTrustSnapshot capture(HookConfig.Configuration configuration) throws IOException {
        return new HookTrustSnapshot(configuration);
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

    synchronized void verify() throws IOException {
        if (invalidated) {
            throw new IOException("Hook 信任已失效，请重启后重新确认");
        }
        try {
            if (!fingerprint.equals(currentFingerprint())) {
                throw new IOException("Hook 配置或脚本发生变化");
            }
        } catch (IOException | RuntimeException e) {
            invalidated = true;
            throw new IOException("Hook 配置或脚本变化/不可读取，本次会话不再执行用户 Hook", e);
        }
    }

    private String currentFingerprint() throws IOException {
        if (!Files.readString(config).equals(source)) {
            throw new IOException("Hook 配置在加载后发生变化");
        }
        MessageDigest digest = digest();
        add(digest, "java-agent-hook-trust-v1");
        add(digest, project.toString());
        add(digest, config.toRealPath().toString());
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
