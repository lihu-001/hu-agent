package dev.huagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSetupTest {
    @TempDir
    Path directory;

    private final StringWriter text = new StringWriter();

    @Test
    void writesAnswersIntoTemplateAndKeepsDefaults() throws IOException {
        Path file = directory.resolve("home/config.properties");
        assertTrue(run("\n中文模型\n\nsk-te\\st\n", file));
        AgentConfig config = AgentConfig.load(file);
        assertEquals(ConfigSetup.DEFAULT_BASE_URL, config.baseUrl());
        assertEquals("中文模型", config.model());
        // 空 api-key 会重新询问；反斜杠按 properties 规则转义后原样读回。
        assertEquals("sk-te\\st", config.apiKey());
        assertTrue(text.toString().contains("不能为空"), text.toString());
        String written = Files.readString(file);
        assertTrue(written.contains("compact.threshold-chars=50000"), written);
        assertFalse(text.toString().contains("sk-te"), "api-key 不应回显到终端输出");
    }

    @Test
    void customBaseUrlIsKept() throws IOException {
        Path file = directory.resolve("config.properties");
        assertTrue(run(" https://llm.example/v1 \nm\nk\n", file));
        assertEquals("https://llm.example/v1", AgentConfig.load(file).baseUrl());
    }

    @Test
    void endOfInputAbortsWithoutWritingFile() throws IOException {
        Path file = directory.resolve("config.properties");
        assertFalse(run("\nmodel\n", file));
        assertFalse(Files.exists(file));
    }

    @Test
    void renderReplacesOnlyRequiredKeys() throws IOException {
        String rendered = ConfigSetup.render(ConfigSetup.template(), "u", "m", "k");
        assertTrue(rendered.contains("\nopenai.base-url=u\n"), rendered);
        assertTrue(rendered.contains("\nopenai.model=m\n"), rendered);
        assertTrue(rendered.contains("\nopenai.api-key=k\n"), rendered);
        assertTrue(rendered.contains("goal.evaluator-model=\n"), rendered);
    }

    private boolean run(String input, Path file) throws IOException {
        return ConfigSetup.run(new BufferedReader(new StringReader(input)), new PrintWriter(text, true), null, file);
    }
}
