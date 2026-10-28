package dev.huagent.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 模型接口类型：两种协议（OpenAI Chat Completions、Anthropic Messages），各分官方与自定义地址。
 * 官方接口的地址有默认值，自定义接口必须填写地址。
 */
public enum ModelProvider {
    OPENAI("openai", "OpenAI 官方", Protocol.OPENAI, "https://api.openai.com/v1", 0),
    ANTHROPIC("anthropic", "Anthropic 官方", Protocol.ANTHROPIC, "https://api.anthropic.com", 32000),
    OPENAI_COMPATIBLE("openai-compatible", "OpenAI 格式自定义接口", Protocol.OPENAI, null, 0),
    ANTHROPIC_COMPATIBLE("anthropic-compatible", "Anthropic 格式自定义接口", Protocol.ANTHROPIC, null, 8192);

    public enum Protocol { OPENAI, ANTHROPIC }

    private final String id;
    private final String label;
    private final Protocol protocol;
    private final String officialBaseUrl;
    private final int defaultMaxOutputTokens;

    ModelProvider(String id, String label, Protocol protocol, String officialBaseUrl, int defaultMaxOutputTokens) {
        this.id = id;
        this.label = label;
        this.protocol = protocol;
        this.officialBaseUrl = officialBaseUrl;
        this.defaultMaxOutputTokens = defaultMaxOutputTokens;
    }

    /** 配置文件与 HU_PROVIDER 中的写法。 */
    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public Protocol protocol() {
        return protocol;
    }

    public boolean official() {
        return officialBaseUrl != null;
    }

    /** 官方接口的默认地址；自定义接口为 null。 */
    public String officialBaseUrl() {
        return officialBaseUrl;
    }

    /**
     * Messages 协议要求每次请求给出 max_tokens：官方模型都支持 32000，兼容接口的模型上限参差不齐，取保守的 8192。
     * Chat Completions 协议不发送该字段，为 0。
     */
    public int defaultMaxOutputTokens() {
        return defaultMaxOutputTokens;
    }

    /** 不区分大小写；无法识别时报错并列出全部写法。 */
    public static ModelProvider parse(String value, String key) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(provider -> provider.id.equals(normalized)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(key + " 必须为 " + Arrays.stream(values())
                        .map(ModelProvider::id).collect(Collectors.joining("、"))));
    }
}
