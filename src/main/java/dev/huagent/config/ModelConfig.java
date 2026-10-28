package dev.huagent.config;

import cn.hutool.core.util.StrUtil;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static dev.huagent.config.PropertiesFile.positiveInt;
import static dev.huagent.config.PropertiesFile.required;

/**
 * 模型接口配置：接口类型、地址、api-key、模型名称与 token 上限。hu 把它单独保存在 HuHome 下的 model.properties，
 * 首次运行时引导填写；教程各阶段与旧版 hu 仍以 openai.* 写在 config.properties 里（见 legacy）。
 */
public record ModelConfig(ModelProvider provider, String baseUrl, String apiKey, String model,
                          int maxContextTokens, int maxOutputTokens) {
    public static final int DEFAULT_MAX_CONTEXT_TOKENS = 1_000_000;
    public static final String TEMPLATE = "/hu-agent/model.example.properties";
    /** 环境变量 → 配置项（不含前缀），非空时优先于文件。 */
    public static final Map<String, String> ENVIRONMENT_KEYS = Map.of(
            "HU_PROVIDER", "provider",
            "HU_BASE_URL", "base-url",
            "HU_MODEL", "model",
            "HU_API_KEY", "api-key");

    public ModelConfig {
        Objects.requireNonNull(provider, "provider");
        if (maxContextTokens <= 0) {
            throw new IllegalArgumentException("max-context-tokens 必须为正整数");
        }
        if (provider.protocol() == ModelProvider.Protocol.ANTHROPIC && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("max-output-tokens 必须为正整数");
        }
    }

    /** hu 的 model.properties，配置项不带前缀；文件不存在时，只要环境变量提供了模型与 api-key 也可以启动。 */
    public static ModelConfig load(Path file, Map<String, String> environment) {
        Properties properties = PropertiesFile.read(file);
        if (properties == null) {
            if (!coveredByEnvironment(environment)) {
                throw new IllegalArgumentException("找不到 " + file.toAbsolutePath()
                        + "，请运行 hu 按提示填写，或从 model.example.properties 复制");
            }
            properties = new Properties();
        }
        return from(properties, "", String.valueOf(file.getFileName()), environment);
    }

    /** config.properties 里以 openai.* 写的模型设置（教程各阶段与旧版 hu 的格式），同样可以用 openai.provider 换接口类型。 */
    public static ModelConfig legacy(Properties properties, Map<String, String> environment) {
        return from(properties, "openai.", HuHome.CONFIG_FILE, environment);
    }

    /** 不需要配置文件也能启动的条件：环境变量给出了模型与 api-key（官方接口的地址有默认值）。 */
    public static boolean coveredByEnvironment(Map<String, String> environment) {
        return StrUtil.isNotBlank(environment.get("HU_MODEL")) && StrUtil.isNotBlank(environment.get("HU_API_KEY"));
    }

    /** 同一接口换一个模型（目标判断器单独配置模型时使用）。 */
    public ModelConfig withModel(String name) {
        return new ModelConfig(provider, baseUrl, apiKey, name, maxContextTokens, maxOutputTokens);
    }

    /**
     * 未填写接口类型时按 OpenAI 协议处理：没有地址视为 OpenAI 官方，有地址视为 OpenAI 格式自定义接口，
     * 与只认 OpenAI 协议时的旧配置行为一致。地址留空时取官方默认值，自定义接口必须填写。
     */
    private static ModelConfig from(Properties file, String prefix, String source, Map<String, String> environment) {
        Properties properties = new Properties();
        properties.putAll(file);
        ENVIRONMENT_KEYS.forEach((variable, key) -> {
            String value = environment.get(variable);
            if (StrUtil.isNotBlank(value)) {
                properties.setProperty(prefix + key, value.strip());
            }
        });
        String configured = properties.getProperty(prefix + "provider");
        String baseUrl = StrUtil.blankToDefault(properties.getProperty(prefix + "base-url"), null);
        ModelProvider provider = StrUtil.isNotBlank(configured) ? ModelProvider.parse(configured, prefix + "provider")
                : baseUrl == null ? ModelProvider.OPENAI : ModelProvider.OPENAI_COMPATIBLE;
        if (baseUrl == null && !provider.official()) {
            throw new IllegalArgumentException(provider.label() + "需要在 " + source + " 中填写 " + prefix + "base-url");
        }
        String outputKey = prefix + "max-output-tokens";
        return new ModelConfig(provider,
                baseUrl == null ? provider.officialBaseUrl() : baseUrl.strip(),
                required(properties, prefix + "api-key", null, source),
                required(properties, prefix + "model", null, source),
                positiveInt(properties, prefix + "max-context-tokens", DEFAULT_MAX_CONTEXT_TOKENS),
                StrUtil.isBlank(properties.getProperty(outputKey)) ? provider.defaultMaxOutputTokens()
                        : positiveInt(properties, outputKey, provider.defaultMaxOutputTokens()));
    }

    @Override
    public String toString() {
        return "ModelConfig[provider=" + provider.id() + ", baseUrl=" + baseUrl + ", apiKey=***, model=" + model + "]";
    }
}
