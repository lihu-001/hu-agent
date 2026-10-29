package dev.huagent.tool.file;

import dev.huagent.app.ToolUseMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.io.Git;
import dev.huagent.io.GitFixture;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolRegistry;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GrepToolTest {
    @TempDir
    Path temporary;
    private Path root;
    private ToolRegistry tools;

    @BeforeEach
    void setUp() throws IOException {
        root = temporary.resolve("workspace");
        // 与 hu 相同的工具和权限：grep 免确认，不会读终端。
        tools = ToolUseMain.createTools(new AgentConfig("http://localhost", "unused", "fake", root,
                10, Duration.ofSeconds(5), 12000), call -> null);
        write("src/main/App.java", "class App {\n    // 上下文剩余\n    int remaining;\n}\n");
        write("src/main/Prompt.java", "class Prompt {\n    String text = \"上下文剩余\";\n}\n");
        write("docs/notes.md", "上下文剩余 在状态栏\n");
    }

    @Test
    void listsMatchingFilesByDefault() throws Exception {
        assertEquals("找到 3 个文件\ndocs/notes.md\nsrc/main/App.java\nsrc/main/Prompt.java",
                grep(Map.of("pattern", "上下文剩余")));
        assertEquals("[无匹配]（搜索了 3 个文件）", grep(Map.of("pattern", "不存在的文字")));
    }

    @Test
    void contentModeShowsLineNumbersAndMergedContext() throws Exception {
        assertEquals("找到 2 处匹配（2 个文件）\n"
                        + "src/main/App.java:2:    // 上下文剩余\n"
                        + "src/main/Prompt.java:2:    String text = \"上下文剩余\";",
                grep(Map.of("pattern", "上下文剩余", "glob", "*.java", "output_mode", "content")));
        assertEquals("找到 1 处匹配（1 个文件）\n"
                        + "src/main/App.java-1-class App {\n"
                        + "src/main/App.java:2:    // 上下文剩余\n"
                        + "src/main/App.java-3-    int remaining;",
                grep(Map.of("pattern", "//", "output_mode", "content", "context", 1)));
    }

    @Test
    void countModeIgnoreCaseGlobAndPathFilters() throws Exception {
        assertEquals("找到 2 处匹配（2 个文件）\nsrc/main/App.java:1\nsrc/main/Prompt.java:1",
                grep(Map.of("pattern", "^CLASS", "ignore_case", true, "output_mode", "count")));
        assertEquals("找到 1 个文件\ndocs/notes.md", grep(Map.of("pattern", "上下文", "path", "docs")));
        assertEquals("找到 1 个文件\nsrc/main/App.java", grep(Map.of("pattern", "class", "glob", "**/App.java")));
        assertEquals("找到 1 个文件\nsrc/main/Prompt.java",
                grep(Map.of("pattern", "class", "path", "src/main/Prompt.java")));
    }

    @Test
    void headLimitTruncatesWithHint() throws Exception {
        assertEquals("找到 3 个文件\ndocs/notes.md\n[只显示了前 1 条，共 3 条；可用 path、glob 缩小范围，或调大 head_limit]",
                grep(Map.of("pattern", "上下文剩余", "head_limit", 1)));
    }

    @Test
    void skipsBinaryAndBuildDirectoriesOutsideGit() throws Exception {
        assumeTrue(outsideGitRepository(), "临时目录位于 git 仓库内");
        Files.write(root.resolve("data.bin"), "上下文剩余\0".getBytes(StandardCharsets.UTF_8));
        write("target/classes/App.java", "上下文剩余");
        write("node_modules/x/index.js", "上下文剩余");
        assertEquals("找到 3 个文件\ndocs/notes.md\nsrc/main/App.java\nsrc/main/Prompt.java",
                grep(Map.of("pattern", "上下文剩余")));
    }

    @Test
    void respectsGitignoreInsideRepositoryButSearchesUntrackedFiles() throws Exception {
        assumeTrue(GitFixture.available(), "本机没有 git");
        GitFixture.init(root);
        write(".gitignore", "build/\n");
        write("build/out.java", "上下文剩余");
        GitFixture.run(root, "add", "src");
        // docs/notes.md 未跟踪也未忽略，照样搜索；build/ 被忽略。
        assertEquals("找到 3 个文件\ndocs/notes.md\nsrc/main/App.java\nsrc/main/Prompt.java",
                grep(Map.of("pattern", "上下文剩余")));
        // path 本身被忽略时退回遍历目录，仍能搜到。
        assertEquals("找到 1 个文件\nbuild/out.java", grep(Map.of("pattern", "上下文剩余", "path", "build")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"pattern\":\"\"}", "{\"pattern\":\"(\"}", "{\"pattern\":\"a\",\"extra\":1}",
            "{\"pattern\":\"a\",\"output_mode\":\"lines\"}", "{\"pattern\":\"a\",\"context\":-1}",
            "{\"pattern\":\"a\",\"head_limit\":0}", "{\"pattern\":\"a\",\"ignore_case\":\"yes\"}",
            "{\"pattern\":\"a\",\"path\":\"missing\"}", "{\"pattern\":\"a\",\"glob\":\"/abs\"}"})
    void invalidArgumentsReturnErrors(String arguments) throws Exception {
        String result = tools.execute(new ToolCall("bad", "grep", arguments));
        assertTrue(result.startsWith("工具错误") || result.startsWith("权限拒绝"), result);
    }

    @Test
    void pathOutsideWorkspaceIsRejected() throws Exception {
        Files.writeString(temporary.resolve("secret.txt"), "上下文剩余");
        String result = grep(Map.of("pattern", "上下文剩余", "path", "../secret.txt"));
        assertEquals("工具错误：路径超出工作目录。", result);
    }

    private boolean outsideGitRepository() {
        return Git.output(temporary, "rev-parse") == null;
    }

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String grep(Map<String, ?> args) throws InterruptedException {
        return tools.execute(new ToolCall("g", "grep", JSON.toJSONString(args)));
    }
}
