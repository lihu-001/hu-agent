package dev.huagent.model;

import dev.huagent.config.ModelConfig;

import java.time.Duration;

/** 按接口类型创建客户端：OpenAI 协议走 Chat Completions，Anthropic 协议走 Messages。 */
public final class ModelClients {
    private ModelClients() {
    }

    public static ModelClient create(ModelConfig config, Duration timeout) {
        return switch (config.provider().protocol()) {
            case OPENAI -> new OpenAiClient(config.baseUrl(), config.apiKey(), config.model(), timeout);
            case ANTHROPIC -> new AnthropicClient(config.baseUrl(), config.apiKey(), config.model(),
                    config.maxOutputTokens(), !config.provider().official(), timeout);
        };
    }
}
