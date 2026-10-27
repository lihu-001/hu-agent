package dev.huagent.config;

import java.nio.file.Path;

/** 项目目录就是运行 hu 时所在的目录：Hook、MCP 与技能配置都从其中的 .agents/ 读取。 */
public final class ProjectDirectory {
    private ProjectDirectory() {
    }

    public static Path resolve() {
        return Path.of("").toAbsolutePath().normalize();
    }
}
