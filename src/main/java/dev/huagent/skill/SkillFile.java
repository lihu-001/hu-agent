package dev.huagent.skill;

import cn.hutool.core.util.StrUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 SKILL.md：开头的 --- 块是 frontmatter（每行 key: value），其后是正文。
 * 只支持单行字符串值，不是完整 YAML；未知字段忽略，便于兼容其他工具的技能文件。
 */
final class SkillFile {
    static final int MAX_FILE_BYTES = 64 * 1024;
    private static final String DELIMITER = "---";

    record Parsed(Map<String, String> fields, String body) {
    }

    private SkillFile() {
    }

    static Parsed read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("缺少 SKILL.md 普通文件");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("SKILL.md 超过 64 KiB");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("SKILL.md 不是有效的 UTF-8");
        }
        return parse(text);
    }

    static Parsed parse(String text) {
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !DELIMITER.equals(lines.get(0).strip())) {
            throw new IllegalArgumentException("SKILL.md 必须以 --- 开头的 frontmatter 开始");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (DELIMITER.equals(line.strip())) {
                String body = String.join("\n", lines.subList(i + 1, lines.size())).strip();
                return new Parsed(fields, body);
            }
            if (StrUtil.isBlank(line) || line.stripLeading().startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("frontmatter 第 " + (i + 1) + " 行应为 key: value");
            }
            String key = line.substring(0, colon).strip();
            if (fields.putIfAbsent(key, unquote(line.substring(colon + 1).strip())) != null) {
                throw new IllegalArgumentException("frontmatter 字段重复：" + key);
            }
        }
        throw new IllegalArgumentException("frontmatter 缺少结束的 ---");
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).strip();
        }
        return value;
    }
}
