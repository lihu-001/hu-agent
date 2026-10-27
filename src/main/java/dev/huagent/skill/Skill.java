package dev.huagent.skill;

import java.nio.file.Path;

/** 启动时只保存技能元数据；正文在 load_skill 调用时才从 file 读取。 */
public record Skill(String name, String description, Path file) {
}
