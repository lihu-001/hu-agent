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
        assertEquals("     1\t你好", call("read_file", Map.of("path", "目录/中文.txt")));
        assertEquals("你好", Files.readString(root.resolve("目录/中文.txt")));
        call("write_file", Map.of("path", "目录/中文.txt", "content", ""));
        assertEquals("", Files.readString(root.resolve("目录/中文.txt")));
        assertEquals("[空文件]", call("read_file", Map.of("path", "目录/中文.txt")));
    }

    @Test
    void editRequiresUniqueLiteralMatchUnlessReplaceAllAndPreservesLineEndings() throws Exception {
        call("write_file", Map.of("path", "edit.txt", "content", "a.$\r\na.$\r\nb\r\n"));
        String ambiguous = call("edit_file", Map.of("path", "edit.txt", "old_text", "a.$", "new_text", "中文"));
        assertTrue(ambiguous.startsWith("工具错误：old_text 在文件中出现了 2 次"), ambiguous);
        assertEquals("a.$\r\na.$\r\nb\r\n", Files.readString(root.resolve("edit.txt")), "不唯一时不修改");
        assertEquals("已编辑 edit.txt。",
                call("edit_file", Map.of("path", "edit.txt", "old_text", "a.$\r\nb", "new_text", "中文")));
        assertEquals("a.$\r\n中文\r\n", Files.readString(root.resolve("edit.txt")));
        call("write_file", Map.of("path", "edit.txt", "content", "a.$\r\na.$\r\n"));
        assertEquals("已编辑 edit.txt（替换了 2 处）。", call("edit_file",
                Map.of("path", "edit.txt", "old_text", "a.$", "new_text", "", "replace_all", true)));
        assertEquals("\r\n\r\n", Files.readString(root.resolve("edit.txt")));
    }

    @Test
    void editHintsWhenOldTextCarriesReadFileLineNumbers() throws Exception {
        call("write_file", Map.of("path", "edit.txt", "content", "first\nsecond\n"));
        String result = call("edit_file", Map.of("path", "edit.txt", "old_text", "     2\tsecond", "new_text", "x"));
        assertTrue(result.startsWith("工具错误：未找到 old_text") && result.contains("行号前缀"), result);
        assertEquals("first\nsecond\n", Files.readString(root.resolve("edit.txt")));
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
        assertEquals("     1\tab", call("read_file", Map.of("path", "edit.txt")));
    }

    @Test
    void readsWithLineNumbersOffsetAndLimitWithoutMisreportingAnEmptyFirstLine() throws Exception {
        call("write_file", Map.of("path", "read.txt", "content", "\n第二行\n第三行\n"));
        assertEquals("     1\t\n[已显示第 1–1 行，共 3 行；用 offset=2 继续读取]",
                call("read_file", Map.of("path", "read.txt", "limit", 1)));
        assertEquals("     1\t\n     2\t第二行\n     3\t第三行", call("read_file", Map.of("path", "read.txt")));
        assertEquals("     2\t第二行\n[已显示第 2–2 行，共 3 行；用 offset=3 继续读取]",
                call("read_file", Map.of("path", "read.txt", "offset", 2, "limit", 1)));
        assertEquals("     3\t第三行", call("read_file", Map.of("path", "read.txt", "offset", 3)));
        assertEquals("工具错误：offset 超出范围：文件共 3 行。",
                call("read_file", Map.of("path", "read.txt", "offset", 4)));
    }

    @Test
    void truncatesAtWholeLinesAndSaysWhereToContinue() throws Exception {
        // 每行显示为 1007 字符（行号 6 + 制表符 + 1000），60000 字符放得下 59 行。
        String line = "字".repeat(1000);
        call("write_file", Map.of("path", "read.txt", "content", (line + "\n").repeat(100)));
        String result = call("read_file", Map.of("path", "read.txt"));
        assertTrue(result.endsWith("\n[已显示第 1–59 行，共 100 行；用 offset=60 继续读取]"),
                result.substring(result.length() - 60));
        assertEquals(59, result.lines().filter(text -> text.endsWith(line)).count());
        assertTrue(call("read_file", Map.of("path", "read.txt", "offset", 60)).startsWith("    60\t" + line));
        call("write_file", Map.of("path", "long.txt", "content", "x".repeat(3000)));
        assertEquals("     1\t" + "x".repeat(2000) + "…（本行共 3000 字符，已截断）",
                call("read_file", Map.of("path", "long.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "not json", "{\"path\":1}", "{\"path\":\" \"}",
            "{\"path\":\"x\",\"extra\":1}", "{\"path\":\"x\",\"limit\":0}",
            "{\"path\":\"x\",\"limit\":-1}", "{\"path\":\"x\",\"limit\":1.5}",
            "{\"path\":\"x\",\"limit\":\"2\"}", "{\"path\":\"x\",\"limit\":null}",
            "{\"path\":\"x\",\"limit\":2147483648}", "{\"path\":\"x\",\"offset\":0}"})
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
        assertEquals("     1\tok", call("read_file", Map.of("path", path)));
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

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void permitsAbsolutePathsThroughJunctionIntoWorkspace() throws Exception {
        // 与 8.3 短文件名同理：字面前缀不同，真实路径仍在工作目录内；本机未必有短文件名，用 junction 复现。
        call("write_file", Map.of("path", "inside.txt", "content", "ok"));
        Path link = temporary.resolve("workspace-alias");
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                link.toString(), root.toString()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(), "创建临时目录 junction 应成功");
        try {
            assertEquals("     1\tok", call("read_file", Map.of("path", link.resolve("inside.txt").toString())));
            call("write_file", Map.of("path", link.resolve("new/file.txt").toString(), "content", "new"));
            assertEquals("new", Files.readString(root.resolve("new/file.txt")));
        } finally {
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
