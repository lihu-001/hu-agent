package dev.huagent.tool.file;

import dev.huagent.app.ToolUseMain;
import dev.huagent.config.AgentConfig;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolRegistry;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

class FileToolsTest {
    @TempDir
    Path temporary;
    private Path root;
    private ToolRegistry tools;

    @BeforeEach
    void setUp() throws IOException {
        root = temporary.resolve("workspace");
        tools = ToolUseMain.createTools(new AgentConfig("http://localhost", "unused", "fake", root,
                10, Duration.ofSeconds(5), 12000));
    }

    @Test
    void writesReadsAndOverwritesUtf8IncludingEmptyContent() throws Exception {
        assertTrue(call("write_file", Map.of("path", "目录/中文.txt", "content", "你好")).contains("6 字节"));
        assertEquals("你好", call("read_file", Map.of("path", "目录/中文.txt")));
        assertEquals("你好", Files.readString(root.resolve("目录/中文.txt")));
        call("write_file", Map.of("path", "目录/中文.txt", "content", ""));
        assertEquals("", Files.readString(root.resolve("目录/中文.txt")));
        assertEquals("[空文件]", call("read_file", Map.of("path", "目录/中文.txt")));
    }

    @Test
    void editsFirstLiteralOccurrenceAndPreservesLineEndings() throws Exception {
        call("write_file", Map.of("path", "edit.txt", "content", "a.$\r\na.$\r\n"));
        call("edit_file", Map.of("path", "edit.txt", "old_text", "a.$", "new_text", "中文"));
        assertEquals("中文\r\na.$\r\n", Files.readString(root.resolve("edit.txt")));
        call("edit_file", Map.of("path", "edit.txt", "old_text", "中文", "new_text", ""));
        assertEquals("\r\na.$\r\n", Files.readString(root.resolve("edit.txt")));
    }

    @Test
    void missingMatchAndEmptyOldTextDoNotChangeFile() throws Exception {
        call("write_file", Map.of("path", "edit.txt", "content", "original"));
        for (String oldText : new String[]{"missing", ""}) {
            assertTrue(call("edit_file", Map.of("path", "edit.txt", "old_text", oldText, "new_text", "new"))
                    .startsWith("工具错误"));
        }
        assertEquals("original", Files.readString(root.resolve("edit.txt")));
    }

    @Test
    void whitespaceIsAValidEditTarget() throws Exception {
        call("write_file", Map.of("path", "edit.txt", "content", "a b"));
        call("edit_file", Map.of("path", "edit.txt", "old_text", " ", "new_text", ""));
        assertEquals("ab", call("read_file", Map.of("path", "edit.txt")));
    }

    @Test
    void limitsLinesAndCharactersWithoutMisreportingAnEmptyFirstLine() throws Exception {
        call("write_file", Map.of("path", "read.txt", "content", "\n第二行\n第三行\n"));
        assertEquals("\n[还有 2 行未显示]", call("read_file", Map.of("path", "read.txt", "limit", 1)));
        assertEquals("\n第二行\n第三行\n", call("read_file", Map.of("path", "read.txt")));
        call("write_file", Map.of("path", "read.txt", "content", "字".repeat(12001)));
        String result = call("read_file", Map.of("path", "read.txt"));
        assertTrue(result.startsWith("字".repeat(12000) + "\n[输出已截断"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "not json", "{\"path\":1}", "{\"path\":\" \"}",
            "{\"path\":\"x\",\"extra\":1}", "{\"path\":\"x\",\"limit\":0}",
            "{\"path\":\"x\",\"limit\":-1}", "{\"path\":\"x\",\"limit\":1.5}",
            "{\"path\":\"x\",\"limit\":\"2\"}", "{\"path\":\"x\",\"limit\":null}",
            "{\"path\":\"x\",\"limit\":2147483648}"})
    void invalidReadArgumentsReturnErrors(String arguments) throws Exception {
        assertTrue(tools.execute(new ToolCall("bad", "read_file", arguments)).startsWith("工具错误"));
    }

    @Test
    void invalidWriteEditAndGlobArgumentsHaveNoSideEffects() throws Exception {
        assertError("write_file", Map.of("path", "x"));
        assertError("write_file", Map.of("path", "x", "content", 123));
        assertError("edit_file", Map.of("path", "x", "old_text", "x"));
        assertError("glob", Map.of("pattern", 123));
        assertFalse(Files.exists(root.resolve("x")));
    }

    @Test
    void missingFilesAndDirectoriesProduceToolErrors() throws Exception {
        assertError("read_file", Map.of("path", "absent"));
        assertError("read_file", Map.of("path", "."));
        assertError("write_file", Map.of("path", ".", "content", "x"));
        assertError("edit_file", Map.of("path", "absent", "old_text", "x", "new_text", "y"));
    }

    @Test
    void refusesLargeAndNonUtf8FilesWithoutEditingThem() throws Exception {
        byte[] large = new byte[WorkspaceFiles.MAX_FILE_BYTES + 1];
        Files.write(root.resolve("large"), large);
        assertError("read_file", Map.of("path", "large", "limit", 1));
        assertError("edit_file", Map.of("path", "large", "old_text", "x", "new_text", "y"));
        assertArrayEquals(large, Files.readAllBytes(root.resolve("large")));
        byte[] invalid = {(byte) 0xff, (byte) 0xfe};
        Files.write(root.resolve("binary"), invalid);
        assertError("read_file", Map.of("path", "binary"));
        assertError("edit_file", Map.of("path", "binary", "old_text", "x", "new_text", "y"));
        assertArrayEquals(invalid, Files.readAllBytes(root.resolve("binary")));
        assertError("write_file", Map.of("path", "new", "content", "x".repeat(WorkspaceFiles.MAX_FILE_BYTES + 1)));
        assertFalse(Files.exists(root.resolve("new")));
    }

    @Test
    void expansionBeyondFileLimitDoesNotChangeOriginal() throws Exception {
        call("write_file", Map.of("path", "edit.txt", "content", "ab"));
        assertError("edit_file", Map.of("path", "edit.txt", "old_text", "a",
                "new_text", "x".repeat(WorkspaceFiles.MAX_FILE_BYTES)));
        assertEquals("ab", Files.readString(root.resolve("edit.txt")));
    }

    @Test
    void rejectsParentAndAbsolutePathsOutsideWorkspace() throws Exception {
        Path outside = temporary.resolve("outside.txt");
        Files.writeString(outside, "private", StandardCharsets.UTF_8);
        for (String path : new String[]{"../outside.txt", outside.toString(),
                "../workspace-other/new.txt"}) {
            assertError("read_file", Map.of("path", path));
            assertError("write_file", Map.of("path", path, "content", "changed"));
            assertError("edit_file", Map.of("path", path, "old_text", "private", "new_text", "changed"));
        }
        assertEquals("private", Files.readString(outside));
        assertFalse(Files.exists(temporary.resolve("workspace-other")));
    }

    @Test
    void permitsAbsolutePathsInsideWorkspace() throws Exception {
        String path = root.resolve("inside.txt").toString();
        call("write_file", Map.of("path", path, "content", "ok"));
        assertEquals("ok", call("read_file", Map.of("path", path)));
    }

    @Test
    void globMatchesZeroOrManyDirectoriesAndSortsRelativePaths() throws Exception {
        for (String path : new String[]{"a.txt", "z.txt", "dir/b.txt", "dir/nested/c.txt", "other.java"}) {
            call("write_file", Map.of("path", path, "content", ""));
        }
        assertEquals("a.txt\ndir/b.txt\ndir/nested/c.txt\nz.txt", call("glob", Map.of("pattern", "**/*.txt")));
        assertEquals("a.txt\nz.txt", call("glob", Map.of("pattern", "*.txt")));
        assertEquals("dir/b.txt\ndir/nested/c.txt", call("glob", Map.of("pattern", "dir/**/*.txt")));
        assertEquals("dir/b.txt", call("glob", Map.of("pattern", "dir/?.txt")));
        assertEquals("[无匹配项]", call("glob", Map.of("pattern", "**/*.missing")));
    }

    @Test
    void globCapsSortedMatches() throws Exception {
        for (int i = 201; i >= 0; i--) {
            Files.writeString(root.resolve("%03d.txt".formatted(i)), "");
        }
        String result = call("glob", Map.of("pattern", "*.txt"));
        assertTrue(result.startsWith("000.txt\n001.txt"));
        assertTrue(result.contains("199.txt\n[匹配项已截断"));
        assertFalse(result.contains("200.txt"));
        assertEquals(201, result.lines().count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../*", "/tmp/*", "C:/*", "**\\*.txt", "a//b", "./*", "[", "**/../*"})
    void globRejectsInvalidOrEscapingPatterns(String pattern) throws Exception {
        assertError("glob", Map.of("pattern", pattern));
    }

    @Test
    void rejectsSymlinkEscapesIncludingNewFilesAndOmitsThemFromGlob() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
            Files.createSymbolicLink(root.resolve("secret-link.txt"), outside.resolve("secret.txt"));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "当前环境不能创建符号链接：" + e.getClass().getSimpleName());
        }
        assertError("read_file", Map.of("path", "link/secret.txt"));
        assertError("write_file", Map.of("path", "link/new/created.txt", "content", "bad"));
        assertError("edit_file", Map.of("path", "secret-link.txt", "old_text", "secret", "new_text", "bad"));
        assertEquals("[无匹配项]", call("glob", Map.of("pattern", "**")));
        assertFalse(Files.exists(outside.resolve("new")));
        assertEquals("secret", Files.readString(outside.resolve("secret.txt")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsWindowsJunctionEscapesAndDoesNotTraverseJunctions() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("junction-outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = root.resolve("junction");
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                link.toString(), outside.toString()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(), "创建临时目录 junction 应成功");
        try {
            assertError("read_file", Map.of("path", "junction/secret.txt"));
            assertError("write_file", Map.of("path", "junction/new/file.txt", "content", "bad"));
            assertError("edit_file", Map.of("path", "junction/secret.txt", "old_text", "secret", "new_text", "bad"));
            assertEquals("[无匹配项]", call("glob", Map.of("pattern", "**")));
            assertFalse(Files.exists(outside.resolve("new")));
            assertEquals("secret", Files.readString(outside.resolve("secret.txt")));
        } finally {
            // 只删除本测试创建的链接本身，不递归操作其目标。
            Files.delete(link);
        }
    }

    private String call(String name, Map<String, Object> args) throws InterruptedException {
        return tools.execute(new ToolCall("test", name, JSON.toJSONString(args)));
    }

    private void assertError(String name, Map<String, Object> args) throws InterruptedException {
        assertTrue(call(name, args).startsWith("工具错误"));
    }
}
