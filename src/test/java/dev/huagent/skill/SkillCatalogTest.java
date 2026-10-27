package dev.huagent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillCatalogTest {
    @TempDir
    Path root;
    private final List<String> warnings = new ArrayList<>();

    @Test
    void parsesFrontmatterAndBody() {
        SkillFile.Parsed parsed = SkillFile.parse("﻿---\nname: demo\n# 注释\ndescription: \"做 A: 和 B\"\n"
                + "extra: 忽略\n---\n\n正文第一行\n---\n第二段\n");
        assertEquals("demo", parsed.fields().get("name"));
        assertEquals("做 A: 和 B", parsed.fields().get("description"));
        assertEquals("正文第一行\n---\n第二段", parsed.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "name: a\n", "---\nname: a\n", "---\nnot a pair\n---\nx", "---\na: 1\na: 2\n---\nx"})
    void rejectsMalformedFrontmatter(String text) {
        assertThrows(IllegalArgumentException.class, () -> SkillFile.parse(text));
    }

    @Test
    void missingDirectoryIsEmptyCatalog() throws IOException {
        SkillCatalog catalog = SkillCatalog.scan(root, warnings::add);
        assertTrue(catalog.skills().isEmpty());
        assertEquals("", catalog.renderCatalog());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void scansSortedSkillsAndRendersOnlyMetadata() throws IOException {
        skill("zeta", "最后一个", "ZETA-BODY");
        skill("alpha", "第一个", "ALPHA-BODY");
        Files.createDirectories(skills().resolve("README.md").getParent());
        Files.writeString(skills().resolve("README.md"), "不是技能目录，忽略");
        SkillCatalog catalog = SkillCatalog.scan(root, warnings::add);
        assertEquals(List.of("alpha", "zeta"), catalog.skills().stream().map(Skill::name).toList());
        String rendered = catalog.renderCatalog();
        assertTrue(rendered.contains("- alpha：第一个\n- zeta：最后一个"), rendered);
        // 关键点：正文不进入目录，只有 load_skill 才读取。
        assertFalse(rendered.contains("ALPHA-BODY"));
        assertEquals("ALPHA-BODY", catalog.body(catalog.find("alpha").orElseThrow()));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void invalidSkillsAreSkippedWithWarningsWithoutBlockingOthers() throws IOException {
        skill("good", "可用", "body");
        write("Bad-Name", "---\nname: Bad-Name\ndescription: x\n---\nbody");
        write("mismatch", "---\nname: other\ndescription: x\n---\nbody");
        write("no-desc", "---\nname: no-desc\n---\nbody");
        write("empty-body", "---\nname: empty-body\ndescription: x\n---\n  \n");
        write("long-desc", "---\nname: long-desc\ndescription: " + "x".repeat(1025) + "\n---\nbody");
        Files.createDirectories(skills().resolve("no-file"));
        Path big = Files.createDirectories(skills().resolve("big")).resolve(SkillCatalog.SKILL_FILE);
        Files.writeString(big, "---\nname: big\ndescription: x\n---\n" + "x".repeat(SkillFile.MAX_FILE_BYTES));
        Path binary = Files.createDirectories(skills().resolve("binary")).resolve(SkillCatalog.SKILL_FILE);
        Files.write(binary, new byte[]{'-', '-', '-', '\n', (byte) 0xC3, (byte) 0x28});
        SkillCatalog catalog = SkillCatalog.scan(root, warnings::add);
        assertEquals(List.of("good"), catalog.skills().stream().map(Skill::name).toList());
        assertEquals(8, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().allMatch(w -> w.startsWith("技能> 跳过 ")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("mismatch") && w.contains("目录名")));
    }

    @Test
    void limitsNumberOfSkills() throws IOException {
        for (int i = 0; i < SkillCatalog.MAX_SKILLS + 2; i++) {
            skill(String.format("s%02d", i), "d", "b");
        }
        SkillCatalog catalog = SkillCatalog.scan(root, warnings::add);
        assertEquals(SkillCatalog.MAX_SKILLS, catalog.skills().size());
        assertEquals(2, warnings.size());
    }

    @Test
    void bodyIsReadAgainOnEachLoadAndRenameIsReported() throws IOException {
        Path file = skill("live", "d", "v1");
        SkillCatalog catalog = SkillCatalog.scan(root, warnings::add);
        Skill skill = catalog.find("live").orElseThrow();
        Files.writeString(file, "---\nname: live\ndescription: d\n---\nv2");
        assertEquals("v2", catalog.body(skill));
        Files.writeString(file, "---\nname: renamed\ndescription: d\n---\nv3");
        assertThrows(IllegalArgumentException.class, () -> catalog.body(skill));
        Files.delete(file);
        assertThrows(IllegalArgumentException.class, () -> catalog.body(skill));
    }

    @Test
    void rejectsSkillFileLinkedOutsideSkillsDirectory() throws IOException {
        Path outside = Files.writeString(root.resolve("outside.md"), "---\nname: link\ndescription: d\n---\nsecret");
        Path link = Files.createDirectories(skills().resolve("link")).resolve(SkillCatalog.SKILL_FILE);
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("当前环境不允许创建符号链接：" + e.getClass().getSimpleName());
        }
        SkillCatalog catalog = SkillCatalog.scan(root, warnings::add);
        assertTrue(catalog.find("link").isEmpty());
        assertTrue(warnings.get(0).contains("技能目录之外"), warnings.toString());
    }

    @Test
    void bundledExampleSkillsAreValid() throws IOException {
        Path module = dev.huagent.config.ProjectDirectory.resolve();
        SkillCatalog catalog = SkillCatalog.scan(module, warnings::add);
        assertEquals(List.of("code-review", "release-notes"), catalog.skills().stream().map(Skill::name).toList());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    private Path skills() {
        return root.resolve(SkillCatalog.DEFAULT_PATH);
    }

    private Path skill(String name, String description, String body) throws IOException {
        return write(name, "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body + "\n");
    }

    private Path write(String folder, String text) throws IOException {
        Path file = Files.createDirectories(skills().resolve(folder)).resolve(SkillCatalog.SKILL_FILE);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }
}
