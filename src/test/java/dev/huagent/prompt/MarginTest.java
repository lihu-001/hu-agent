package dev.huagent.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarginTest {
    private final Margin margin = new Margin("●", "⎿");

    @Test
    void topLevelLinesGetTwoColumnsWhileBulletsAndIndentedLinesStay() {
        assertEquals("  后台> bg_1 completed\n● 回复\n  第二行\n  ⎿  2 行\n\n",
                margin.apply("后台> bg_1 completed\n● 回复\n  第二行\n  ⎿  2 行\n\n", 80));
    }

    @Test
    void longLinesWrapAtSpacesAndContinueUnderTheText() {
        // 每行最多 columns - 1 = 29 列。
        assertEquals(List.of("  后台> bg_2 failed: ./mvnw", "  -o -q test，下一次请求模型", "  时通知。"),
                margin.layout("后台> bg_2 failed: ./mvnw -o -q test，下一次请求模型时通知。", 29));
        assertEquals(List.of("● 先看看 read_file 和", "  grep 的结果"), margin.layout("● 先看看 read_file 和 grep 的结果", 22));
        assertEquals(List.of("  ⎿  bg_2 [running] 18848", "     ms：mvn test"),
                margin.layout("  ⎿  bg_2 [running] 18848 ms：mvn test", 25));
    }

    @Test
    void wordsTooLongForALineAreBrokenByColumns() {
        assertEquals(List.of("  shell：src/main/java/dev/", "  huagent/HuMain.java"),
                margin.layout("shell：src/main/java/dev/huagent/HuMain.java", 27));
        // 放得进新的一行的词整个换行，不从中间折开。
        assertEquals(List.of("  shell：git diff --", "  src/main/java/dev/huagent"),
                margin.layout("shell：git diff -- src/main/java/dev/huagent", 27));
        // 中文占两列，放不下的字整个换到下一行。
        assertEquals(List.of("  一二三四五六", "  七八九十"), margin.layout("一二三四五六七八九十", 15));
    }

    @Test
    void escapeSequencesTakeNoColumnsAndAreKept() {
        String painted = "\u001b[1;33m【自动批准】\u001b[0m\u001b[1mshell\u001b[0m：git diff --check README.md";
        assertEquals(List.of("  " + painted), margin.layout(painted, 50));
        assertEquals(List.of("  \u001b[1;33m【自动批准】\u001b[0m\u001b[1mshell\u001b[0m：git diff", "  --check README.md"),
                margin.layout(painted, 31));
        // 颜色包住的 ● 仍是段首，不补边距。
        assertEquals(List.of("\u001b[32m●\u001b[0m \u001b[1mshell\u001b[0m"),
                margin.layout("\u001b[32m●\u001b[0m \u001b[1mshell\u001b[0m", 80));
    }

    @Test
    void lineEndingsArePreservedAndPromptsWithoutNewlineStayUnfinished() {
        assertEquals("  提示> 一\r\n", margin.apply("提示> 一\r\n", 80));
        assertEquals("  aaaa bbbb\r\n  cccc\r\n", margin.apply("aaaa bbbb cccc\r\n", 13));
        assertEquals("  【权限确认】shell：ls  仅允许本次调用？[y/N] ",
                margin.apply("【权限确认】shell：ls  仅允许本次调用？[y/N] ", 80));
        assertEquals("  [y/N] ", margin.apply("  [y/N] ", 80));
    }

    @Test
    void spacesAtTheEndOfARowAreDroppedWithoutLeavingAnEmptyRow() {
        assertEquals(List.of("  abcdefghij", "  klm"), margin.layout("abcdefghij   klm", 12));
        assertEquals(List.of("  abcdefghij"), margin.layout("abcdefghij   ", 12));
    }

    @Test
    void tabsAreExpandedOnlyWhenWrapping() {
        assertEquals(List.of("  a\tb"), margin.layout("a\tb", 40));
        assertEquals(List.of("  a     b ccccccccccc", "  dd"), margin.layout("a\tb ccccccccccc dd", 21));
    }

    @Test
    void veryNarrowTerminalsAreLeftToTheTerminal() {
        assertEquals(List.of("  一二三四五六七八九十"), margin.layout("一二三四五六七八九十", 11));
    }

    @Test
    void fallbackSymbolsWorkTheSame() {
        Margin ascii = new Margin("*", "-");
        assertEquals("* 回复\n  -  2 行\n  提示\n", ascii.apply("* 回复\n  -  2 行\n提示\n", 80));
    }
}
