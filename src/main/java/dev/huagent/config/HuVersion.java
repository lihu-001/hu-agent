package dev.huagent.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** 构建时由 Maven 写入 hu-agent/version.properties；直接从 IDE 运行未经过资源过滤时显示 dev。 */
public final class HuVersion {
    private static final String RESOURCE = "/hu-agent/version.properties";

    private HuVersion() {
    }

    public static String current() {
        try (InputStream stream = HuVersion.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return "dev";
            }
            Properties properties = new Properties();
            properties.load(stream);
            String version = properties.getProperty("version", "").strip();
            return version.isEmpty() || version.startsWith("${") ? "dev" : version;
        } catch (IOException e) {
            return "dev";
        }
    }
}
