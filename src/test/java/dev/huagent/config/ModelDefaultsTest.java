package dev.huagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelDefaultsTest {
    @TempDir
    Path directory;

    @Test
    void replacesOnlyTheModelLineAndKeepsCommentsAndOtherKeys() {
        String text = """
                # 默认模型
                provider=openai-compatible
                model=gpt-6-astra
                models=gpt-6-astra,glm-5.3-flash
                #model=commented
                api-key=sk-x
                """;
        assertEquals(text.replace("model=gpt-6-astra\n", "model=glm-5.3-flash\n"),
                ModelDefaults.replace(text, "model", "glm-5.3-flash"));
    }

    @Test
    void keepsLineEndingsBomAndIndentationAndReplacesEveryOccurrence() {
        String bom = String.valueOf((char) 0xFEFF);
        String text = bom + "model=a\r\napi-key=k\r\n  model : b\r\n";
        assertEquals(bom + "model=c\r\napi-key=k\r\n  model=c\r\n", ModelDefaults.replace(text, "model", "c"));
    }

    @Test
    void dropsContinuationLinesOfTheReplacedEntryOnly() {
        String text = "model=very\\\n    long\nmodels=a,\\\n  model=b\nmax-context-tokens=1\n";
        assertEquals("model=x\nmodels=a,\\\n  model=b\nmax-context-tokens=1\n",
                ModelDefaults.replace(text, "model", "x"));
    }

    @Test
    void appendsMissingKeyAndEscapesBackslashes() {
        assertEquals("api-key=k\nmodel=a\\\\b\n", ModelDefaults.replace("api-key=k\n", "model", "a\\b"));
        assertEquals("api-key=k\nmodel=m", ModelDefaults.replace("api-key=k", "model", "m"));
        assertEquals("model=m\n", ModelDefaults.replace("", "model", "m"));
    }

    @Test
    void savesIntoModelFileOrLegacyConfigAndSkipsEnvironmentOverride() throws IOException {
        Path config = Files.writeString(directory.resolve("config.properties"),
                "openai.model=old\nopenai.api-key=k\nagent.max-model-calls=10\n", StandardCharsets.UTF_8);
        Path model = directory.resolve("model.properties");

        ModelDefaults legacy = ModelDefaults.locate(config, model, Map.of());
        assertEquals(config, legacy.file());
        legacy.save("glm-5.3-flash");
        assertEquals("openai.model=glm-5.3-flash\nopenai.api-key=k\nagent.max-model-calls=10\n",
                Files.readString(config, StandardCharsets.UTF_8));

        Files.writeString(model, "model=old\napi-key=k\n", StandardCharsets.UTF_8);
        ModelDefaults current = ModelDefaults.locate(config, model, Map.of("HU_MODEL", "  "));
        assertEquals(model, current.file());
        current.save("gpt-6-astra");
        assertEquals("model=gpt-6-astra\napi-key=k\n", Files.readString(model, StandardCharsets.UTF_8));
        assertEquals("gpt-6-astra", ModelConfig.load(model, Map.of()).model());
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count(), "临时文件不留在配置目录");
        }

        ModelDefaults environment = ModelDefaults.locate(config, model, Map.of("HU_MODEL", "env"));
        assertNull(environment.file());
        assertThrows(IllegalStateException.class, () -> environment.save("x"));
        assertThrows(IllegalArgumentException.class, () -> current.save("two names"));
        assertEquals("model=gpt-6-astra\napi-key=k\n", Files.readString(model, StandardCharsets.UTF_8));
    }
}
