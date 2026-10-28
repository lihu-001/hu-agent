package dev.huagent.config;

import cn.hutool.core.util.StrUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

public record AgentConfig(String baseUrl, String apiKey, String model, Path workDirectory,
                          int maxModelCalls, Duration shellTimeout, int maxOutputChars,
                          int compactThresholdChars, int compactKeepToolResults, Duration backgroundTimeout,
                          int schedulerPollSeconds, boolean teamEnabled, int goalBlockCap,
                          String goalEvaluatorModel, int maxContextTokens) {
    public static final int DEFAULT_COMPACT_THRESHOLD_CHARS = 50000;
    public static final int DEFAULT_MAX_CONTEXT_TOKENS = 1_000_000;
    public static final int DEFAULT_COMPACT_KEEP_TOOL_RESULTS = 3;
    public static final int DEFAULT_BACKGROUND_TIMEOUT_SECONDS = 600;
    public static final int DEFAULT_SCHEDULER_POLL_SECONDS = 15;
    public static final int DEFAULT_GOAL_BLOCK_CAP = 8;
    /** 环境变量 → 配置项。 */
    public static final Map<String, String> ENVIRONMENT_KEYS = Map.of(
            "HU_BASE_URL", "openai.base-url",
            "HU_MODEL", "openai.model",
            "HU_API_KEY", "openai.api-key");

    /** S17：goalEvaluatorModel 为空白时视为 null，判断器与主 Agent 使用同一模型。 */
    public AgentConfig {
        if (goalBlockCap <= 0) {
            throw new IllegalArgumentException("goal.block-cap 必须为正整数");
        }
        if (maxContextTokens <= 0) {
            throw new IllegalArgumentException("openai.max-context-tokens 必须为正整数");
        }
        goalEvaluatorModel = StrUtil.isBlank(goalEvaluatorModel) ? null : goalEvaluatorModel.strip();
    }

    /** S17—S18 的字段；模型最大上下文取默认值。 */
    public AgentConfig(String baseUrl, String apiKey, String model, Path workDirectory,
                       int maxModelCalls, Duration shellTimeout, int maxOutputChars,
                       int compactThresholdChars, int compactKeepToolResults, Duration backgroundTimeout,
                       int schedulerPollSeconds, boolean teamEnabled, int goalBlockCap, String goalEvaluatorModel) {
        this(baseUrl, apiKey, model, workDirectory, maxModelCalls, shellTimeout, maxOutputChars,
                compactThresholdChars, compactKeepToolResults, backgroundTimeout, schedulerPollSeconds, teamEnabled,
                goalBlockCap, goalEvaluatorModel, DEFAULT_MAX_CONTEXT_TOKENS);
    }

    /** S13—S16 的字段；目标续跑上限取默认值，判断器使用主模型。 */
    public AgentConfig(String baseUrl, String apiKey, String model, Path workDirectory,
                       int maxModelCalls, Duration shellTimeout, int maxOutputChars,
                       int compactThresholdChars, int compactKeepToolResults, Duration backgroundTimeout,
                       int schedulerPollSeconds, boolean teamEnabled) {
        this(baseUrl, apiKey, model, workDirectory, maxModelCalls, shellTimeout, maxOutputChars,
                compactThresholdChars, compactKeepToolResults, backgroundTimeout, schedulerPollSeconds, teamEnabled,
                DEFAULT_GOAL_BLOCK_CAP, null);
    }

    /** S12 的字段；团队模式默认关闭，行为与 S12 相同。 */
    public AgentConfig(String baseUrl, String apiKey, String model, Path workDirectory,
                       int maxModelCalls, Duration shellTimeout, int maxOutputChars,
                       int compactThresholdChars, int compactKeepToolResults, Duration backgroundTimeout,
                       int schedulerPollSeconds) {
        this(baseUrl, apiKey, model, workDirectory, maxModelCalls, shellTimeout, maxOutputChars,
                compactThresholdChars, compactKeepToolResults, backgroundTimeout, schedulerPollSeconds, false);
    }

    /** S07 及之前的字段；压缩、后台与调度设置取默认值。 */
    public AgentConfig(String baseUrl, String apiKey, String model, Path workDirectory,
                       int maxModelCalls, Duration shellTimeout, int maxOutputChars) {
        this(baseUrl, apiKey, model, workDirectory, maxModelCalls, shellTimeout, maxOutputChars,
                DEFAULT_COMPACT_THRESHOLD_CHARS, DEFAULT_COMPACT_KEEP_TOOL_RESULTS);
    }

    /** S10 及之前的字段；后台命令超时与调度间隔取默认值。 */
    public AgentConfig(String baseUrl, String apiKey, String model, Path workDirectory,
                       int maxModelCalls, Duration shellTimeout, int maxOutputChars,
                       int compactThresholdChars, int compactKeepToolResults) {
        this(baseUrl, apiKey, model, workDirectory, maxModelCalls, shellTimeout, maxOutputChars,
                compactThresholdChars, compactKeepToolResults,
                Duration.ofSeconds(DEFAULT_BACKGROUND_TIMEOUT_SECONDS));
    }

    /** S11 的字段；调度检查间隔取默认值。 */
    public AgentConfig(String baseUrl, String apiKey, String model, Path workDirectory,
                       int maxModelCalls, Duration shellTimeout, int maxOutputChars,
                       int compactThresholdChars, int compactKeepToolResults, Duration backgroundTimeout) {
        this(baseUrl, apiKey, model, workDirectory, maxModelCalls, shellTimeout, maxOutputChars,
                compactThresholdChars, compactKeepToolResults, backgroundTimeout,
                DEFAULT_SCHEDULER_POLL_SECONDS);
    }

    public static AgentConfig load(Path path) {
        return load(path, Map.of());
    }

    /**
     * 环境变量（见 ENVIRONMENT_KEYS）非空时优先于文件中的同名设置；
     * 文件不存在时，只要环境变量提供了模型与 api-key 也可以启动。工作目录默认是运行 hu 时所在的目录。
     */
    public static AgentConfig load(Path path, Map<String, String> environment) {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            // 兼容 Windows 编辑器保存的 UTF-8 BOM，避免污染第一个配置项的名称。
            reader.mark(1);
            if (reader.read() != '\uFEFF') {
                reader.reset();
            }
            properties.load(reader);
        } catch (NoSuchFileException e) {
            if (!coveredByEnvironment(environment)) {
                throw new IllegalArgumentException("找不到 " + path.toAbsolutePath()
                        + "，请运行 hu 按提示填写，或从 config.example.properties 复制");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取配置文件：" + path.toAbsolutePath());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("配置文件格式错误，请检查 properties 转义格式");
        }
        ENVIRONMENT_KEYS.forEach((variable, key) -> {
            String value = environment.get(variable);
            if (StrUtil.isNotBlank(value)) {
                properties.setProperty(key, value.strip());
            }
        });
        return new AgentConfig(
                required(properties, "openai.base-url", "https://api.openai.com/v1"),
                required(properties, "openai.api-key", null),
                required(properties, "openai.model", null),
                Path.of(required(properties, "agent.work-dir", ".")),
                positiveInt(properties, "agent.max-model-calls", 10),
                Duration.ofSeconds(positiveInt(properties, "shell.timeout-seconds", 30)),
                positiveInt(properties, "shell.max-output-chars", 12000),
                positiveInt(properties, "compact.threshold-chars", DEFAULT_COMPACT_THRESHOLD_CHARS),
                positiveInt(properties, "compact.keep-tool-results", DEFAULT_COMPACT_KEEP_TOOL_RESULTS),
                Duration.ofSeconds(positiveInt(properties, "background.timeout-seconds",
                        DEFAULT_BACKGROUND_TIMEOUT_SECONDS)),
                positiveInt(properties, "scheduler.poll-seconds", DEFAULT_SCHEDULER_POLL_SECONDS),
                bool(properties, "team.enabled", false),
                positiveInt(properties, "goal.block-cap", DEFAULT_GOAL_BLOCK_CAP),
                properties.getProperty("goal.evaluator-model"),
                positiveInt(properties, "openai.max-context-tokens", DEFAULT_MAX_CONTEXT_TOKENS));
    }

    /** 不需要配置文件也能启动的条件：环境变量给出了模型与 api-key（接口地址有默认值）。 */
    public static boolean coveredByEnvironment(Map<String, String> environment) {
        return StrUtil.isNotBlank(environment.get("HU_MODEL")) && StrUtil.isNotBlank(environment.get("HU_API_KEY"));
    }

    /** S17：判断器实际使用的模型；未单独配置时与主 Agent 相同。 */
    public String evaluatorModel() {
        return goalEvaluatorModel == null ? model : goalEvaluatorModel;
    }

    /** S13：换一个工作目录（worktree 队友使用），其他设置不变。 */
    public AgentConfig withWorkDirectory(Path directory) {
        return new AgentConfig(baseUrl, apiKey, model, directory, maxModelCalls, shellTimeout, maxOutputChars,
                compactThresholdChars, compactKeepToolResults, backgroundTimeout, schedulerPollSeconds, teamEnabled,
                goalBlockCap, goalEvaluatorModel, maxContextTokens);
    }

    /** S13：打开或关闭团队模式，其他设置不变。 */
    public AgentConfig withTeamEnabled(boolean enabled) {
        return new AgentConfig(baseUrl, apiKey, model, workDirectory, maxModelCalls, shellTimeout, maxOutputChars,
                compactThresholdChars, compactKeepToolResults, backgroundTimeout, schedulerPollSeconds, enabled,
                goalBlockCap, goalEvaluatorModel, maxContextTokens);
    }

    /** 只接受 true / false（不区分大小写），拼错时报错而不是悄悄关闭。 */
    private static boolean bool(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key, String.valueOf(defaultValue)).strip();
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(key + " 必须为 true 或 false");
    }

    private static int positiveInt(Properties properties, String key, int defaultValue) {
        try {
            int value = Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)).strip());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 只展示配置名称，避免错误信息回显配置内容。
        }
        throw new IllegalArgumentException(key + " 必须为正整数");
    }

    private static String required(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key, defaultValue);
        if (StrUtil.isBlank(value)) {
            throw new IllegalArgumentException("请在 config.properties 中填写 " + key);
        }
        return value.strip();
    }

    @Override
    public String toString() {
        return "AgentConfig[baseUrl=" + baseUrl + ", apiKey=***, model=" + model + "]";
    }
}
