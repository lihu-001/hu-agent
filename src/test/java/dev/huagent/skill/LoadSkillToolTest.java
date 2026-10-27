package dev.huagent.skill;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoadSkillToolTest {
    @TempDir
    Path root;
    private LoadSkillTool tool;

    @BeforeEach
    void setUp() throws IOException {
        Path file = Files.createDirectories(root.resolve(SkillCatalog.DEFAULT_PATH).resolve("demo"))
                .resolve(SkillCatalog.SKILL_FILE);
        Files.writeString(file, "---\nname: demo\ndescription: 演示\n---\n第一步\n第二步\n");
        tool = new LoadSkillTool(SkillCatalog.scan(root, w -> fail(w)));
    }

    @Test
    void definitionListsSkillNamesAndRequiresName() {
        assertEquals("load_skill", tool.definition().name());
        assertTrue(tool.definition().description().contains("可用技能：demo"));
        assertEquals(List.of("name"),
                tool.definition().parameters().getJSONArray("required").toJavaList(String.class));
        assertTrue(new LoadSkillTool(SkillCatalog.empty()).definition().description().contains("当前没有可用技能"));
    }

    @Test
    void returnsWrappedBody() throws IOException {
        assertEquals("<skill name=\"demo\">\n第一步\n第二步\n</skill>", tool.execute("{\"name\":\" demo \"}"));
    }

    @Test
    void validatesArgumentsAndUnknownNames() {
        assertThrows(IllegalArgumentException.class, () -> tool.execute("not json"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("{}"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("{\"name\":\" \"}"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("{\"name\":1}"));
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(JSON.toJSONString(Map.of("name", "demo", "extra", 1))));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> tool.execute("{\"name\":\"../demo\"}"));
        assertTrue(unknown.getMessage().contains("可用技能：demo"), unknown.getMessage());
    }

    @Test
    void longBodyIsTruncated() throws IOException {
        Path file = root.resolve(SkillCatalog.DEFAULT_PATH).resolve("demo").resolve(SkillCatalog.SKILL_FILE);
        Files.writeString(file, "---\nname: demo\ndescription: 演示\n---\n" + "x".repeat(SkillCatalog.MAX_BODY_CHARS + 5));
        String result = tool.execute("{\"name\":\"demo\"}");
        assertTrue(result.contains("技能正文已截断"));
        assertEquals(SkillCatalog.MAX_BODY_CHARS, result.chars().filter(c -> c == 'x').count());
    }
}
