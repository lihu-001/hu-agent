package dev.huagent.config;

import cn.hutool.core.util.StrUtil;

import java.nio.file.Path;

/**
 * hu-agent 的用户目录：model.properties、config.properties、Hook 与 MCP 信任记录，以及安装器放置的 app/ 与 runtime/。
 * 启动脚本按自身位置传入 -Dhu.home；其次是环境变量 HU_HOME；都没有时为 ~/.hu-agent。
 */
public final class HuHome {
    public static final String PROPERTY = "hu.home";
    public static final String ENVIRONMENT = "HU_HOME";
    public static final String DIRECTORY_NAME = ".hu-agent";
    public static final String CONFIG_FILE = "config.properties";
    public static final String MODEL_FILE = "model.properties";

    private HuHome() {
    }

    public static Path resolve() {
        return resolve(System.getProperty(PROPERTY), System.getenv(ENVIRONMENT), System.getProperty("user.home"));
    }

    static Path resolve(String property, String environment, String userHome) {
        String directory = StrUtil.isNotBlank(property) ? property
                : StrUtil.isNotBlank(environment) ? environment : null;
        Path home = directory == null ? Path.of(userHome, DIRECTORY_NAME) : Path.of(directory.strip());
        return home.toAbsolutePath().normalize();
    }

    public static Path configFile() {
        return resolve().resolve(CONFIG_FILE);
    }

    public static Path modelFile() {
        return resolve().resolve(MODEL_FILE);
    }
}
