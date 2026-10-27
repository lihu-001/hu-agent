package dev.huagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {
    @TempDir
    Path directory;

    @Test
    void readsUtf8ConfigWithBomAndTrimsValues() throws IOException {
        Path file = write("\uFEFFopenai.base-url=https://example.com/v1\n"
                + "openai.model= 中文模型 \nopenai.api-key= test-secret \n");
        AgentConfig config = AgentConfig.load(file);
        assertEquals("https://example.com/v1", config.baseUrl());
        assertEquals("中文模型", config.model());
        assertEquals("test-secret", config.apiKey());
        assertFalse(config.toString().contains("test-secret"));
    }

    @Test
    void usesDefaultBaseUrlWhenOmitted() throws IOException {
        AgentConfig config = AgentConfig.load(write("openai.model=test-model\nopenai.api-key=test-secret\n"));
        assertEquals("https://api.openai.com/v1", config.baseUrl());
        assertEquals(Path.of("."), config.workDirectory());
        assertEquals(10, config.maxModelCalls());
        assertEquals(30, config.shellTimeout().toSeconds());
        assertEquals(12000, config.maxOutputChars());
        assertEquals(50000, config.compactThresholdChars());
        assertEquals(3, config.compactKeepToolResults());
        assertEquals(600, config.backgroundTimeout().toSeconds());
        assertEquals(15, config.schedulerPollSeconds());
    }

    @Test
    void environmentOverridesFileValues() throws IOException {
        Path file = write("openai.base-url=https://file.example/v1\nopenai.model=file-model\nopenai.api-key=file-key\n");
        AgentConfig config = AgentConfig.load(file, Map.of("HU_MODEL", " env-model ", "HU_API_KEY", "env-key",
                "HU_BASE_URL", "   "));
        assertEquals("env-model", config.model());
        assertEquals("env-key", config.apiKey());
        // 空白的环境变量视为未设置，保留文件中的值。
        assertEquals("https://file.example/v1", config.baseUrl());
    }

    @Test
    void environmentAloneIsEnoughWhenFileIsMissing() {
        Path missing = directory.resolve("config.properties");
        Map<String, String> environment = Map.of("HU_MODEL", "env-model", "HU_API_KEY", "env-key");
        assertTrue(AgentConfig.coveredByEnvironment(environment));
        AgentConfig config = AgentConfig.load(missing, environment);
        assertEquals("env-model", config.model());
        assertEquals("https://api.openai.com/v1", config.baseUrl());
        assertFalse(AgentConfig.coveredByEnvironment(Map.of("HU_MODEL", "env-model")));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(missing, Map.of("HU_MODEL", "m")));
    }

    @Test
    void teamModeIsOffByDefaultAndOnlyAcceptsTrueOrFalse() throws IOException {
        assertFalse(AgentConfig.load(write("openai.model=t\nopenai.api-key=t\n")).teamEnabled());
        assertTrue(AgentConfig.load(write("openai.model=t\nopenai.api-key=t\nteam.enabled= TRUE \n")).teamEnabled());
        assertFalse(AgentConfig.load(write("openai.model=t\nopenai.api-key=t\nteam.enabled=false\n")).teamEnabled());
        Path typo = write("openai.model=t\nopenai.api-key=t\nteam.enabled=yes\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(typo))
                .getMessage().contains("team.enabled"));
        AgentConfig on = AgentConfig.load(write("openai.model=t\nopenai.api-key=t\nteam.enabled=true\n"));
        assertTrue(on.withWorkDirectory(Path.of("x")).teamEnabled(), "换工作目录不改变其他设置");
    }

    @Test
    void readsSchedulerPollIntervalAndRejectsInvalid() throws IOException {
        AgentConfig config = AgentConfig.load(write("openai.model=t\nopenai.api-key=t\nscheduler.poll-seconds=5\n"));
        assertEquals(5, config.schedulerPollSeconds());
        Path bad = write("openai.model=t\nopenai.api-key=t\nscheduler.poll-seconds=0\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(bad))
                .getMessage().contains("scheduler.poll-seconds"));
        Path text = write("openai.model=t\nopenai.api-key=t\nscheduler.poll-seconds=abc\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(text))
                .getMessage().contains("scheduler.poll-seconds"));
    }

    @Test
    void goalSettingsDefaultToBlockCapEightAndMainModel() throws IOException {
        AgentConfig defaults = AgentConfig.load(write("openai.model=main\nopenai.api-key=t\ngoal.evaluator-model=  \n"));
        assertEquals(8, defaults.goalBlockCap());
        assertNull(defaults.goalEvaluatorModel(), "空白视为未配置");
        assertEquals("main", defaults.evaluatorModel());
        AgentConfig custom = AgentConfig.load(write("openai.model=main\nopenai.api-key=t\n"
                + "goal.block-cap=3\ngoal.evaluator-model= judge \nteam.enabled=true\n"));
        assertEquals(3, custom.goalBlockCap());
        assertEquals("judge", custom.evaluatorModel());
        AgentConfig moved = custom.withWorkDirectory(Path.of("x")).withTeamEnabled(false);
        assertEquals(3, moved.goalBlockCap(), "换工作目录或团队开关不改变目标设置");
        assertEquals("judge", moved.goalEvaluatorModel());
        Path bad = write("openai.model=t\nopenai.api-key=t\ngoal.block-cap=0\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(bad))
                .getMessage().contains("goal.block-cap"));
    }

    @Test
    void readsBackgroundTimeoutAndRejectsInvalid() throws IOException {
        AgentConfig config = AgentConfig.load(write("openai.model=t\nopenai.api-key=t\nbackground.timeout-seconds=90\n"));
        assertEquals(90, config.backgroundTimeout().toSeconds());
        Path bad = write("openai.model=t\nopenai.api-key=t\nbackground.timeout-seconds=0\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(bad))
                .getMessage().contains("background.timeout-seconds"));
    }

    @Test
    void readsCompactSettingsAndRejectsInvalid() throws IOException {
        AgentConfig config = AgentConfig.load(write("openai.model=t\nopenai.api-key=t\n"
                + "compact.threshold-chars=800\ncompact.keep-tool-results=1\n"));
        assertEquals(800, config.compactThresholdChars());
        assertEquals(1, config.compactKeepToolResults());
        Path bad = write("openai.model=t\nopenai.api-key=t\ncompact.threshold-chars=0\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(bad))
                .getMessage().contains("compact.threshold-chars"));
    }

    @Test
    void readsToolLimits() throws IOException {
        AgentConfig config = AgentConfig.load(write("openai.model=test\nopenai.api-key=test\n"
                + "agent.work-dir=练习\nagent.max-model-calls=3\nshell.timeout-seconds=2\nshell.max-output-chars=100\n"));
        assertEquals(Path.of("练习"), config.workDirectory());
        assertEquals(3, config.maxModelCalls());
        assertEquals(2, config.shellTimeout().toSeconds());
        assertEquals(100, config.maxOutputChars());
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent.max-model-calls=0", "agent.max-model-calls=-1",
            "shell.timeout-seconds=abc", "shell.max-output-chars=2147483648", "shell.max-output-chars="})
    void rejectsInvalidLimits(String setting) throws IOException {
        Path file = write("openai.model=test\nopenai.api-key=test\n" + setting + "\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(file))
                .getMessage().contains(setting.split("=")[0]));
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai.model", "openai.api-key", "openai.base-url"})
    void rejectsBlankValuesAndNamesTheKey(String key) throws IOException {
        Path file = write("openai.model=test-model\nopenai.api-key=test-secret\n" + key + "=   \n");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(file));
        assertTrue(error.getMessage().contains(key));
        assertFalse(error.getMessage().contains("test-secret"));
    }

    @Test
    void missingRequiredKeyNamesTheKey() throws IOException {
        Path file = write("openai.model=test-model\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(file))
                .getMessage().contains("openai.api-key"));
    }

    @Test
    void missingFileExplainsHowToCreateIt() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AgentConfig.load(directory.resolve("config.properties")));
        assertTrue(error.getMessage().contains("config.example.properties"));
    }

    @Test
    void malformedPropertiesDoNotLeakContents() throws IOException {
        Path file = write("openai.api-key=test-secret\\uZZZZ\n");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(file));
        assertTrue(error.getMessage().contains("格式"));
        assertFalse(error.getMessage().contains("test-secret"));
    }

    private Path write(String contents) throws IOException {
        return Files.writeString(directory.resolve("config.properties"), contents, StandardCharsets.UTF_8);
    }
}
