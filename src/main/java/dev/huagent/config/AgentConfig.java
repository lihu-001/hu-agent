package dev.huagent.config;

import cn.hutool.core.util.StrUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static dev.huagent.config.PropertiesFile.bool;
import static dev.huagent.config.PropertiesFile.positiveInt;
import static dev.huagent.config.PropertiesFile.required;

/** 模型接口配置见 ModelConfig（hu 单独保存在 model.properties），其余设置来自 config.properties。 */
public record AgentConfig(ModelConfig modelConfig, Path workDirectory,
                          int maxModelCalls, Duration shellTimeout, int maxOutputChars,
                          int compactThresholdChars, int compactKeepToolResults, Duration backgroundTimeout,
                          int schedulerPollSeconds, boolean teamEnabled, int goalBlockCap,
                          String goalEvaluatorModel) {
    public static final int DEFAULT_COMPACT_THRESHOLD_CHARS = 50000;
    public static final int DEFAULT_COMPACT_KEEP_TOOL_RESULTS = 3;
    public static final int DEFAULT_BACKGROUND_TIMEOUT_SECONDS = 600;
    public static final int DEFAULT_SCHEDULER_POLL_SECONDS = 15;
    public static final int DEFAULT_GOAL_BLOCK_CAP = 8;

    /** S17：goalEvaluatorModel 为空白时视为 null，判断器与主 Agent 使用同一模型。 */
    public AgentConfig {
        Objects.requireNonNull(modelConfig, "modelConfig");
        if (goalBlockCap <= 0) {
            throw new IllegalArgumentException("goal.block-cap 必须为正整数");
        }
        goalEvaluatorModel = StrUtil.isBlank(goalEvaluatorModel) ? null : goalEvaluatorModel.strip();
    }

    /** S17—S18 的字段加模型最大上下文；接口按 OpenAI 协议。 */
    public AgentConfig(String baseUrl, String apiKey, String model, Path workDirectory,
                       int maxModelCalls, Duration shellTimeout, int maxOutputChars,
                       int compactThresholdChars, int compactKeepToolResults, Duration backgroundTimeout,
                       int schedulerPollSeconds, boolean teamEnabled, int goalBlockCap, String goalEvaluatorModel,
                       int maxContextTokens) {
        this(new ModelConfig(ModelProvider.OPENAI_COMPATIBLE, baseUrl, apiKey, model, maxContextTokens, 0),
                workDirectory, maxModelCalls, shellTimeout, maxOutputChars, compactThresholdChars,
                compactKeepToolResults, backgroundTimeout, schedulerPollSeconds, teamEnabled, goalBlockCap,
                goalEvaluatorModel);
    }

    /** S17—S18 的字段；模型最大上下文取默认值。 */
    public AgentConfig(String baseUrl, String apiKey, String model, Path workDirectory,
                       int maxModelCalls, Duration shellTimeout, int maxOutputChars,
                       int compactThresholdChars, int compactKeepToolResults, Duration backgroundTimeout,
                       int schedulerPollSeconds, boolean teamEnabled, int goalBlockCap, String goalEvaluatorModel) {
        this(baseUrl, apiKey, model, workDirectory, maxModelCalls, shellTimeout, maxOutputChars,
                compactThresholdChars, compactKeepToolResults, backgroundTimeout, schedulerPollSeconds, teamEnabled,
                goalBlockCap, goalEvaluatorModel, ModelConfig.DEFAULT_MAX_CONTEXT_TOKENS);
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
     * 教程各阶段的格式：一个文件同时保存模型（openai.*，见 ModelConfig.legacy）与其他设置。
     * 环境变量（见 ModelConfig.ENVIRONMENT_KEYS）非空时优先于文件；文件不存在时，只要环境变量提供了模型与
     * api-key 也可以启动。工作目录默认是运行 hu 时所在的目录。
     */
    public static AgentConfig load(Path path, Map<String, String> environment) {
        Properties properties = PropertiesFile.read(path);
        if (properties == null) {
            if (!ModelConfig.coveredByEnvironment(environment)) {
                throw new IllegalArgumentException("找不到 " + path.toAbsolutePath()
                        + "，请运行 hu 按提示填写，或从 config.example.properties 复制");
            }
            properties = new Properties();
        }
        return from(properties, ModelConfig.legacy(properties, environment));
    }

    /**
     * hu 的格式：模型设置读 modelFile，其他设置读 configFile（不存在时全部取默认值）。
     * modelFile 不存在时退回 configFile 中旧版的 openai.* 设置，升级前写好的配置继续可用。
     */
    public static AgentConfig load(Path configFile, Path modelFile, Map<String, String> environment) {
        Properties properties = Objects.requireNonNullElseGet(PropertiesFile.read(configFile), Properties::new);
        ModelConfig model = Files.exists(modelFile) ? ModelConfig.load(modelFile, environment)
                : ModelConfig.legacy(properties, environment);
        return from(properties, model);
    }

    /** hu 首次运行是否需要引导配置模型：model.properties、旧版的 openai.model 与环境变量都没有提供模型时需要。 */
    public static boolean modelConfigured(Path configFile, Path modelFile, Map<String, String> environment) {
        if (Files.exists(modelFile) || ModelConfig.coveredByEnvironment(environment)) {
            return true;
        }
        Properties properties = PropertiesFile.read(configFile);
        return properties != null && StrUtil.isNotBlank(properties.getProperty("openai.model"));
    }

    private static AgentConfig from(Properties properties, ModelConfig model) {
        return new AgentConfig(model,
                Path.of(required(properties, "agent.work-dir", ".", HuHome.CONFIG_FILE)),
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
                properties.getProperty("goal.evaluator-model"));
    }

    public String baseUrl() {
        return modelConfig.baseUrl();
    }

    public String apiKey() {
        return modelConfig.apiKey();
    }

    public String model() {
        return modelConfig.model();
    }

    public int maxContextTokens() {
        return modelConfig.maxContextTokens();
    }

    /** S17：判断器实际使用的模型；未单独配置时与主 Agent 相同。 */
    public String evaluatorModel() {
        return goalEvaluatorModel == null ? model() : goalEvaluatorModel;
    }

    /** S13：换一个工作目录（worktree 队友使用），其他设置不变。 */
    public AgentConfig withWorkDirectory(Path directory) {
        return new AgentConfig(modelConfig, directory, maxModelCalls, shellTimeout, maxOutputChars,
                compactThresholdChars, compactKeepToolResults, backgroundTimeout, schedulerPollSeconds, teamEnabled,
                goalBlockCap, goalEvaluatorModel);
    }

    /** S13：打开或关闭团队模式，其他设置不变。 */
    public AgentConfig withTeamEnabled(boolean enabled) {
        return new AgentConfig(modelConfig, workDirectory, maxModelCalls, shellTimeout, maxOutputChars,
                compactThresholdChars, compactKeepToolResults, backgroundTimeout, schedulerPollSeconds, enabled,
                goalBlockCap, goalEvaluatorModel);
    }

    @Override
    public String toString() {
        return "AgentConfig[baseUrl=" + baseUrl() + ", apiKey=***, model=" + model() + "]";
    }
}
