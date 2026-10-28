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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSetupTest {
    @TempDir
    Path directory;

    private final StringWriter text = new StringWriter();

    @Test
    void officialProviderSkipsBaseUrlAndWritesBothFiles() throws IOException {
        Path modelFile = directory.resolve("home/model.properties");
        Path configFile = directory.resolve("home/config.properties");
        // 无效序号会重新询问；空 api-key 会重新询问；反斜杠按 properties 规则转义后原样读回。
        assertTrue(run("5\n2\n中文模型\n\nsk-te\\st\n", modelFile, configFile));
        AgentConfig config = AgentConfig.load(configFile, modelFile, Map.of());
        assertEquals(ModelProvider.ANTHROPIC, config.modelConfig().provider());
        assertEquals("https://api.anthropic.com", config.baseUrl());
        assertEquals("中文模型", config.model());
        assertEquals("sk-te\\st", config.apiKey());
        assertTrue(text.toString().contains("请输入 1—4 之间的序号"), text.toString());
        assertTrue(text.toString().contains("不能为空"), text.toString());
        assertFalse(text.toString().contains("sk-te"), "api-key 不应回显到终端输出");
        for (String option : new String[]{"1) OpenAI 官方（https://api.openai.com/v1）",
                "2) Anthropic 官方（https://api.anthropic.com）", "3) OpenAI 格式自定义接口（Chat Completions 协议",
                "4) Anthropic 格式自定义接口（Messages 协议"}) {
            assertTrue(text.toString().contains(option), option + "\n" + text);
        }
        assertTrue(Files.readString(configFile).contains("compact.threshold-chars=50000"));
        assertTrue(Files.readString(modelFile).contains("max-context-tokens=1000000"));
    }

    @Test
    void customProviderAsksAndValidatesBaseUrl() throws IOException {
        Path modelFile = directory.resolve("model.properties");
        assertTrue(run("anthropic-compatible\nllm.example\n https://llm.example/anthropic \nm\nk\n", modelFile,
                directory.resolve("config.properties")));
        assertTrue(text.toString().contains("http:// 或 https://"), text.toString());
        ModelConfig config = ModelConfig.load(modelFile, Map.of());
        assertEquals(ModelProvider.ANTHROPIC_COMPATIBLE, config.provider());
        assertEquals("https://llm.example/anthropic", config.baseUrl());

        assertTrue(run("3\nhttps://llm.example/v1\nm\nk\n", modelFile, directory.resolve("config.properties")));
        assertEquals(ModelProvider.OPENAI_COMPATIBLE, ModelConfig.load(modelFile, Map.of()).provider());
    }

    @Test
    void existingConfigFileIsLeftUntouched() throws IOException {
        Path configFile = Files.writeString(directory.resolve("config.properties"), "agent.max-model-calls=3\n");
        assertTrue(run("1\nm\nk\n", directory.resolve("model.properties"), configFile));
        assertEquals("agent.max-model-calls=3\n", Files.readString(configFile));
    }

    @Test
    void endOfInputAbortsWithoutWritingFiles() throws IOException {
        Path modelFile = directory.resolve("model.properties");
        Path configFile = directory.resolve("config.properties");
        assertFalse(run("4\nhttps://llm.example\nmodel\n", modelFile, configFile));
        assertFalse(Files.exists(modelFile));
        assertFalse(Files.exists(configFile));
    }

    @Test
    void renderReplacesOnlyRequiredKeys() throws IOException {
        String rendered = ConfigSetup.render(ConfigSetup.template(ModelConfig.TEMPLATE),
                ModelProvider.OPENAI_COMPATIBLE, "u", "m", "k");
        assertTrue(rendered.contains("\nprovider=openai-compatible\n"), rendered);
        assertTrue(rendered.contains("\nbase-url=u\n"), rendered);
        assertTrue(rendered.contains("\nmodel=m\n"), rendered);
        assertTrue(rendered.contains("\napi-key=k\n"), rendered);
        assertTrue(rendered.contains("\nmax-output-tokens=\n"), rendered);
    }

    private boolean run(String input, Path modelFile, Path configFile) throws IOException {
        return ConfigSetup.run(new BufferedReader(new StringReader(input)), new PrintWriter(text, true), null,
                modelFile, configFile);
    }
}
