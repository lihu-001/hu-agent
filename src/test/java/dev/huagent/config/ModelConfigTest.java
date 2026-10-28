package dev.huagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelConfigTest {
    @TempDir
    Path directory;

    @Test
    void officialProvidersUseDefaultBaseUrl() throws IOException {
        ModelConfig openai = load("﻿provider=openai\nbase-url=\nmodel= gpt \napi-key= secret \n");
        assertEquals(ModelProvider.OPENAI, openai.provider());
        assertEquals("https://api.openai.com/v1", openai.baseUrl());
        assertEquals("gpt", openai.model());
        assertEquals("secret", openai.apiKey());
        assertEquals(ModelConfig.DEFAULT_MAX_CONTEXT_TOKENS, openai.maxContextTokens());

        ModelConfig anthropic = load("provider=anthropic\nmodel=claude\napi-key=k\n");
        assertEquals("https://api.anthropic.com", anthropic.baseUrl());
        assertEquals(32000, anthropic.maxOutputTokens());
        // 官方接口也可以填地址（例如走代理）。
        assertEquals("https://proxy.example", load("provider=anthropic\nbase-url=https://proxy.example\n"
                + "model=claude\napi-key=k\n").baseUrl());
    }

    @Test
    void customProvidersRequireBaseUrl() throws IOException {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load("provider=anthropic-compatible\nbase-url=  \nmodel=m\napi-key=secret\n"));
        assertTrue(error.getMessage().contains("model.properties") && error.getMessage().contains("base-url"),
                error.getMessage());
        assertFalse(error.getMessage().contains("secret"));

        ModelConfig custom = load("provider=anthropic-compatible\nbase-url=https://llm.example/anthropic\n"
                + "model=m\napi-key=k\n");
        assertEquals(ModelProvider.ANTHROPIC_COMPATIBLE, custom.provider());
        assertEquals("https://llm.example/anthropic", custom.baseUrl());
        assertEquals(8192, custom.maxOutputTokens(), "兼容接口默认取保守的输出上限");

        assertThrows(IllegalArgumentException.class, () -> load("provider=openai-compatible\nmodel=m\napi-key=k\n"));
    }

    @Test
    void missingProviderMeansOpenAiProtocol() throws IOException {
        assertEquals(ModelProvider.OPENAI, load("model=m\napi-key=k\n").provider());
        assertEquals(ModelProvider.OPENAI_COMPATIBLE, load("base-url=https://llm.example/v1\nmodel=m\napi-key=k\n")
                .provider());
    }

    @Test
    void providerIsCaseInsensitiveAndUnknownValuesListChoices() throws IOException {
        assertEquals(ModelProvider.ANTHROPIC, load("provider= Anthropic \nmodel=m\napi-key=k\n").provider());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load("provider=gemini\nmodel=m\napi-key=k\n"));
        assertTrue(error.getMessage().contains("openai、anthropic、openai-compatible、anthropic-compatible"),
                error.getMessage());
    }

    @Test
    void readsTokenLimitsAndRejectsInvalid() throws IOException {
        ModelConfig config = load("provider=anthropic\nmodel=m\napi-key=k\nmax-context-tokens=200000\n"
                + "max-output-tokens=64000\n");
        assertEquals(200_000, config.maxContextTokens());
        assertEquals(64_000, config.maxOutputTokens());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> load("provider=anthropic\nmodel=m\napi-key=k\nmax-output-tokens=0\n"))
                .getMessage().contains("max-output-tokens"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> load("model=m\napi-key=k\nmax-context-tokens=abc\n"))
                .getMessage().contains("max-context-tokens"));
    }

    @Test
    void environmentOverridesFileValues() throws IOException {
        Path file = write("provider=openai\nmodel=file-model\napi-key=file-key\n");
        ModelConfig config = ModelConfig.load(file, Map.of("HU_PROVIDER", "anthropic-compatible",
                "HU_BASE_URL", " https://env.example ", "HU_MODEL", "env-model", "HU_API_KEY", "  "));
        assertEquals(ModelProvider.ANTHROPIC_COMPATIBLE, config.provider());
        assertEquals("https://env.example", config.baseUrl());
        assertEquals("env-model", config.model());
        // 空白的环境变量视为未设置，保留文件中的值。
        assertEquals("file-key", config.apiKey());
    }

    @Test
    void missingFileNeedsEnvironment() {
        Path missing = directory.resolve("model.properties");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ModelConfig.load(missing, Map.of()))
                .getMessage().contains("model.example.properties"));
        ModelConfig config = ModelConfig.load(missing, Map.of("HU_PROVIDER", "anthropic", "HU_MODEL", "m",
                "HU_API_KEY", "k"));
        assertEquals(ModelProvider.ANTHROPIC, config.provider());
    }

    @Test
    void secretsStayOutOfTextAndWithModelKeepsTheRest() throws IOException {
        ModelConfig config = load("provider=anthropic\nmodel=main\napi-key=test-secret\n");
        assertFalse(config.toString().contains("test-secret"));
        ModelConfig judge = config.withModel("judge");
        assertEquals("judge", judge.model());
        assertEquals(config.provider(), judge.provider());
        assertEquals(config.apiKey(), judge.apiKey());
        assertEquals(config.maxOutputTokens(), judge.maxOutputTokens());
    }

    private ModelConfig load(String contents) throws IOException {
        return ModelConfig.load(write(contents), Map.of());
    }

    private Path write(String contents) throws IOException {
        return Files.writeString(directory.resolve("model.properties"), contents, StandardCharsets.UTF_8);
    }
}
