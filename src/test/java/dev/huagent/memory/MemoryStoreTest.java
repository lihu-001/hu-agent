package dev.huagent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {
    @TempDir
    Path root;
    private MemoryStore store;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = new MemoryStore(root.resolve(MemoryStore.DIRECTORY_NAME));
    }

    @Test
    void missingDirectoryMeansNoMemories() throws IOException {
        assertEquals(List.of(), store.list(warnings::add));
        assertEquals("", MemoryStore.renderIndex(List.of()));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void writeThenListSortedAndRenderIndexWithoutBody() throws IOException {
        assertTrue(store.write("prefer-junit5", MemoryType.FEEDBACK, "测试用 JUnit 5", "原因：项目统一\n适用：新测试"));
        assertTrue(store.write("user-role", MemoryType.USER, "用户是 Java 后端开发", "熟悉 Spring"));
        List<MemoryEntry> entries = store.list(warnings::add);
        assertEquals(List.of("prefer-junit5", "user-role"), entries.stream().map(MemoryEntry::name).toList());
        assertEquals(MemoryType.FEEDBACK, entries.get(0).type());
        String index = MemoryStore.renderIndex(entries);
        assertTrue(index.contains("- prefer-junit5 [feedback]：测试用 JUnit 5"), index);
        assertTrue(index.contains("- user-role [user]：用户是 Java 后端开发"), index);
        assertFalse(index.contains("熟悉 Spring"), "正文不进索引");
        assertTrue(warnings.isEmpty());
    }

    @Test
    void fileFormatIsReadableMarkdownAndReadReturnsBody() throws IOException {
        store.write("deploy-doc", MemoryType.REFERENCE, "部署文档位置", "  https://example.com/deploy  ");
        String text = Files.readString(store.directory().resolve("deploy-doc.md"), StandardCharsets.UTF_8);
        assertEquals("---\nname: deploy-doc\ntype: reference\ndescription: 部署文档位置\n---\n\nhttps://example.com/deploy\n",
                text);
        assertEquals("<memory name=\"deploy-doc\" type=\"reference\">\n描述：部署文档位置\n\n"
                + "https://example.com/deploy\n</memory>", store.read("deploy-doc"));
    }

    @Test
    void overwriteReplacesWholeMemoryAndLeavesNoTempFiles() throws IOException {
        assertTrue(store.write("a", MemoryType.PROJECT, "旧", "旧正文"));
        assertFalse(store.write("a", MemoryType.PROJECT, "新", "新正文"));
        assertTrue(store.read("a").contains("新正文"));
        assertFalse(store.read("a").contains("旧正文"));
        try (Stream<Path> files = Files.list(store.directory())) {
            assertEquals(List.of("a.md"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void readReflectsManualEdits() throws IOException {
        store.write("a", MemoryType.USER, "描述", "第一版");
        Path file = store.directory().resolve("a.md");
        Files.writeString(file, Files.readString(file).replace("第一版", "手动修改"));
        assertTrue(store.read("a").contains("手动修改"));
    }

    @Test
    void deleteRemovesFile() throws IOException {
        store.write("a", MemoryType.USER, "描述", "正文");
        store.delete("a");
        assertEquals(List.of(), store.list(warnings::add));
        assertThrows(IllegalArgumentException.class, () -> store.delete("a"));
        assertThrows(IllegalArgumentException.class, () -> store.read("a"));
    }

    @Test
    void rejectsInvalidNamesIncludingPathTraversal() {
        for (String name : List.of("", "../x", "a/b", "A", "a_b", "a--b", "-a", "x".repeat(65))) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.write(name, MemoryType.USER, "d", "c"), name);
            assertThrows(IllegalArgumentException.class, () -> store.read(name), name);
        }
        assertFalse(Files.exists(root.resolve("x.md")));
    }

    @Test
    void validatesDescriptionAndContent() {
        assertThrows(IllegalArgumentException.class, () -> store.write("a", MemoryType.USER, " ", "c"));
        assertThrows(IllegalArgumentException.class, () -> store.write("a", MemoryType.USER, "两\n行", "c"));
        assertThrows(IllegalArgumentException.class,
                () -> store.write("a", MemoryType.USER, "d".repeat(MemoryStore.MAX_DESCRIPTION_CHARS + 1), "c"));
        assertThrows(IllegalArgumentException.class, () -> store.write("a", MemoryType.USER, "d", " "));
        assertThrows(IllegalArgumentException.class,
                () -> store.write("a", MemoryType.USER, "d", "c".repeat(MemoryStore.MAX_CONTENT_CHARS + 1)));
        assertThrows(IllegalArgumentException.class, () -> MemoryType.parse("secret"));
        assertFalse(Files.exists(store.directory().resolve("a.md")));
    }

    @Test
    void invalidFilesAreSkippedWithWarning() throws IOException {
        store.write("good", MemoryType.USER, "有效", "正文");
        Path dir = store.directory();
        Files.writeString(dir.resolve("no-front.md"), "只有正文");
        Files.writeString(dir.resolve("wrong-name.md"), "---\nname: other\ntype: user\ndescription: d\n---\nx");
        Files.writeString(dir.resolve("bad-type.md"), "---\nname: bad-type\ntype: secret\ndescription: d\n---\nx");
        Files.writeString(dir.resolve("empty-body.md"), "---\nname: empty-body\ntype: user\ndescription: d\n---\n");
        Files.write(dir.resolve("not-utf8.md"), new byte[]{(byte) 0xff, (byte) 0xfe});
        Files.writeString(dir.resolve("notes.txt"), "非 .md 文件被忽略");
        List<MemoryEntry> entries = store.list(warnings::add);
        assertEquals(List.of("good"), entries.stream().map(MemoryEntry::name).toList());
        assertEquals(5, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().allMatch(w -> w.startsWith("记忆> 跳过 ")), warnings.toString());
    }

    @Test
    void enforcesMemoryLimitOnCreateButAllowsOverwrite() throws IOException {
        for (int i = 0; i < MemoryStore.MAX_MEMORIES; i++) {
            store.write("m" + i, MemoryType.PROJECT, "d", "c");
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> store.write("extra", MemoryType.PROJECT, "d", "c"));
        assertTrue(error.getMessage().contains("上限"));
        assertFalse(store.write("m0", MemoryType.PROJECT, "d", "更新"));
    }

    @Test
    void directoryPathThatIsAFileFailsListing() throws IOException {
        Files.writeString(root.resolve(MemoryStore.DIRECTORY_NAME), "x");
        assertThrows(IOException.class, () -> store.list(warnings::add));
    }
}
