package dev.huagent.permission;

import dev.huagent.permission.ChangePreview.Kind;
import dev.huagent.permission.ChangePreview.Line;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ChangePreviewTest {
    private static final String FILE = IntStream.rangeClosed(1, 10).mapToObj(i -> "line " + i)
            .collect(Collectors.joining("\n", "", "\n"));

    @Test
    void editShowsWholeLinesWithFileLineNumbersAndCollapsedContext() {
        // old_text 从行中间开始；预览按整行显示，行号是文件里的行号，改动前后各带两行上下文。
        ChangePreview preview = ChangePreview.edit("a.txt", FILE, "e 5\nline 6", "e 5\nline 6\nline 6.5", false);
        assertEquals("a.txt（+1 −0）", preview.summary());
        assertEquals(List.of(new Line(Kind.CONTEXT, 5, "line 5"), new Line(Kind.CONTEXT, 6, "line 6"),
                new Line(Kind.ADDED, 7, "line 6.5"), new Line(Kind.CONTEXT, 8, "line 7"),
                new Line(Kind.CONTEXT, 9, "line 8")), preview.lines());
    }

    @Test
    void replacedLinesShowRemovalBeforeAddition() {
        String content = "a\nb\nc\nd\ne\nf\ng\n";
        ChangePreview preview = ChangePreview.edit("x", content, "b\nc\nd\ne\nf", "b\nc\nD\ne\nf", false);
        assertEquals(List.of(new Line(Kind.CONTEXT, 2, "b"), new Line(Kind.CONTEXT, 3, "c"),
                new Line(Kind.REMOVED, 4, "d"), new Line(Kind.ADDED, 4, "D"),
                new Line(Kind.CONTEXT, 5, "e"), new Line(Kind.CONTEXT, 6, "f")), preview.lines());
        assertEquals("x（+1 −1）", preview.summary());
    }

    @Test
    void longUnchangedRunsCollapseIntoANote() {
        String before = "import a;\n" + "x\n".repeat(10) + "end\n";
        ChangePreview preview = ChangePreview.edit("A.java", before, before, "import a;\nimport b;\n" + "x\n".repeat(10)
                + "END\n", false);
        List<Line> lines = preview.lines();
        assertEquals(new Line(Kind.ADDED, 2, "import b;"), lines.get(1));
        assertTrue(lines.contains(new Line(Kind.NOTE, 0, "⋯ 6 行未改动")), lines.toString());
        assertEquals(new Line(Kind.ADDED, 13, "END"), lines.getLast());
        assertEquals(new Line(Kind.REMOVED, 12, "end"), lines.get(lines.size() - 2));
    }

    @Test
    void oldTextEndingWithNewlineAndDeletionsAreHandled() {
        ChangePreview preview = ChangePreview.edit("a.txt", "a\nb\nc\n", "b\n", "", false);
        assertEquals(List.of(new Line(Kind.CONTEXT, 1, "a"), new Line(Kind.REMOVED, 2, "b"),
                new Line(Kind.CONTEXT, 2, "c")), preview.lines());
        assertEquals(List.of(new Line(Kind.CONTEXT, 2, "b"), new Line(Kind.CONTEXT, 3, "c"), new Line(Kind.ADDED, 4, "d")),
                ChangePreview.edit("a.txt", "a\nb\nc", "c", "c\nd", false).lines(), "文件末尾没有换行也能预览");
    }

    @Test
    void editNotesAmbiguousOrReplaceAllAndReturnsNullWhenNotFound() {
        String content = "x = 1\ny = 2\nx = 1\n";
        assertEquals(new Line(Kind.NOTE, 0, "old_text 在文件中出现了 2 次，工具会拒绝执行"),
                ChangePreview.edit("a", content, "x = 1", "x = 3", false).lines().getLast());
        assertEquals(new Line(Kind.NOTE, 0, "replace_all：共替换 2 处，只显示第一处"),
                ChangePreview.edit("a", content, "x = 1", "x = 3", true).lines().getLast());
        assertNull(ChangePreview.edit("a", content, "missing", "x", false));
    }

    @Test
    void crlfFilesShowCleanLines() {
        ChangePreview preview = ChangePreview.edit("w.txt", "a\r\nb\r\n", "b", "c", false);
        assertEquals(List.of(new Line(Kind.CONTEXT, 1, "a"), new Line(Kind.REMOVED, 2, "b"), new Line(Kind.ADDED, 2, "c")),
                preview.lines());
    }

    @Test
    void writeShowsNewFileHeadOrDiffAgainstExistingContent() {
        String content = IntStream.rangeClosed(1, 25).mapToObj(i -> "row " + i).collect(Collectors.joining("\n"));
        ChangePreview created = ChangePreview.write("new.txt", null, content);
        assertEquals("new.txt（新建，25 行）", created.summary());
        assertEquals(new Line(Kind.ADDED, 1, "row 1"), created.lines().getFirst());
        assertEquals(new Line(Kind.NOTE, 0, "还有 5 行未显示"), created.lines().getLast());
        assertEquals("new.txt（新建，0 行）", ChangePreview.write("new.txt", null, "").summary());

        ChangePreview overwrite = ChangePreview.write("a.txt", FILE, FILE.replace("line 9", "line nine"));
        assertEquals("a.txt（覆盖已有文件，+1 −1）", overwrite.summary());
        assertTrue(overwrite.lines().contains(new Line(Kind.REMOVED, 9, "line 9")), overwrite.lines().toString());
        assertEquals("a.txt（内容与现有文件相同）", ChangePreview.write("a.txt", FILE, FILE).summary());
    }

    @Test
    void multiLineCommandsAreShownLineByLineAndSingleLineCommandsAreNot() {
        assertNull(ChangePreview.command("mvn test"));
        ChangePreview preview = ChangePreview.command("cd src\nmvn test");
        assertEquals("多行命令，共 2 行", preview.summary());
        assertEquals(List.of(new Line(Kind.PLAIN, 0, "cd src"), new Line(Kind.PLAIN, 0, "mvn test")), preview.lines());
    }

    @Test
    void largeChangesAreCappedWithANote() {
        String content = IntStream.rangeClosed(1, 100).mapToObj(i -> "row " + i).collect(Collectors.joining("\n"));
        ChangePreview preview = ChangePreview.write("a.txt", "", content);
        assertEquals(ChangePreview.MAX_LINES + 1, preview.lines().size());
        assertEquals(new Line(Kind.NOTE, 0, "还有 40 行未显示"), preview.lines().getLast());
        assertEquals("a.txt（覆盖已有文件，+100 −0）", preview.summary(), "摘要里的数字按全部改动计算");
    }
}
