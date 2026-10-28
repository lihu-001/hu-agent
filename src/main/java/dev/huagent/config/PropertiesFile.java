package dev.huagent.config;

import cn.hutool.core.util.StrUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;

/** config.properties 与 model.properties 共用的读取与取值规则；错误信息只展示配置名称，不回显配置内容。 */
final class PropertiesFile {
    private PropertiesFile() {
    }

    /** 文件不存在时返回 null，由调用方决定是否允许。 */
    static Properties read(Path path) {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            // 兼容 Windows 编辑器保存的 UTF-8 BOM，避免污染第一个配置项的名称。
            reader.mark(1);
            if (reader.read() != '﻿') {
                reader.reset();
            }
            properties.load(reader);
            return properties;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取配置文件：" + path.toAbsolutePath());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("配置文件 " + path.getFileName() + " 格式错误，请检查 properties 转义格式");
        }
    }

    static String required(Properties properties, String key, String defaultValue, String source) {
        String value = properties.getProperty(key, defaultValue);
        if (StrUtil.isBlank(value)) {
            throw new IllegalArgumentException("请在 " + source + " 中填写 " + key);
        }
        return value.strip();
    }

    static int positiveInt(Properties properties, String key, int defaultValue) {
        try {
            int value = Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)).strip());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 只展示配置名称，避免错误信息回显配置内容。
        }
        throw new IllegalArgumentException(key + " 必须为正整数");
    }

    /** 只接受 true / false（不区分大小写），拼错时报错而不是悄悄关闭。 */
    static boolean bool(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key, String.valueOf(defaultValue)).strip();
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(key + " 必须为 true 或 false");
    }
}
