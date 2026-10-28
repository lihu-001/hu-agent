package dev.huagent.prompt;

import dev.huagent.command.CommandResult;
import dev.huagent.command.SlashCommand;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SlashMenuTest {
    /** /cmd01 … /cmd15，说明为 "命令 N"。 */
    private static final List<SlashCommand> COMMANDS = IntStream.rangeClosed(1, 15)
            .mapToObj(i -> SlashCommand.local("cmd%02d".formatted(i), "命令 " + i, (a, c) -> CommandResult.print("")))
            .toList();

    private final SlashMenu menu = new SlashMenu(new SlashCompleter(() -> COMMANDS));

    @Test
    void showsTenCandidatesAndThePositionWhenThereAreMore() {
        assertTrue(menu.update("/", true, 80, 24));
        List<String> lines = lines();
        assertEquals(11, lines.size(), lines.toString());
        assertEquals("  /cmd01  命令 1", lines.getFirst());
        assertEquals("  /cmd10  命令 10", lines.get(9));
        assertEquals("  1-10 / 15  ↑↓ 选择 · 滚轮滚动", lines.get(10));
    }

    @Test
    void fewCandidatesHaveNoPositionLine() {
        assertTrue(menu.update("/cmd1", true, 80, 24));
        assertEquals(List.of("  /cmd10  命令 10", "  /cmd11  命令 11", "  /cmd12  命令 12",
                "  /cmd13  命令 13", "  /cmd14  命令 14", "  /cmd15  命令 15"), lines());
    }

    @Test
    void hiddenWithoutCandidatesOrWhenTheCursorIsNotAtTheEnd() {
        assertFalse(menu.update("", true, 80, 24));
        assertFalse(menu.update("/zz", true, 80, 24));
        assertFalse(menu.update("/cmd01 ", true, 80, 24));
        assertFalse(menu.update("/cmd", false, 80, 24));
        assertNull(menu.choice(true));
    }

    @Test
    void arrowsMoveTheSelectionAndScrollTheList() {
        menu.update("/", true, 80, 24);
        assertNull(menu.choice(false), "初始不选中");
        assertEquals("/cmd01", menu.choice(true).value(), "Tab 未选中时取第一个");

        move(11);
        assertEquals("/cmd11", menu.choice(false).value());
        assertEquals("  /cmd02  命令 2", lines().getFirst(), "列表跟着下移一行");
        assertEquals("  2-11 / 15  ↑↓ 选择 · 滚轮滚动", lines().get(10));
        assertSelected("/cmd11");

        move(4);
        assertEquals("/cmd15", menu.choice(false).value());
        move(1);
        assertEquals("/cmd01", menu.choice(false).value(), "越过末尾绕回第一个");
        assertEquals("  /cmd01  命令 1", lines().getFirst());
        menu.move(-1);
        assertEquals("/cmd15", menu.choice(false).value(), "越过开头绕回最后一个");
    }

    @Test
    void upWithoutSelectionPicksTheLastVisibleRow() {
        menu.update("/", true, 80, 24);
        menu.move(-1);
        assertEquals("/cmd10", menu.choice(false).value());
    }

    @Test
    void wheelScrollsWithinBoundsAndKeepsTheSelectionVisible() {
        menu.update("/", true, 80, 24);
        menu.scroll(3);
        assertEquals("  /cmd04  命令 4", lines().getFirst());
        assertNull(menu.choice(false), "滚轮不选中");
        menu.move(1);
        assertEquals("/cmd04", menu.choice(false).value(), "↓ 从可见的第一行开始");

        menu.scroll(100);
        assertEquals("  /cmd06  命令 6", lines().getFirst(), "最后一屏停在末尾");
        assertEquals("/cmd06", menu.choice(false).value(), "选中项停在可见区域的上边缘");
        menu.scroll(-100);
        assertEquals("  /cmd01  命令 1", lines().getFirst());
        assertEquals("/cmd06", menu.choice(false).value(), "仍在可见区域内的选中项不动");
    }

    @Test
    void typingResetsSelectionAndScroll() {
        menu.update("/", true, 80, 24);
        move(12);
        menu.update("/c", true, 80, 24);
        assertNull(menu.choice(false));
        assertEquals("  /cmd01  命令 1", lines().getFirst());

        // 输入不变的重画（如窗口缩放）保留选中项。
        menu.move(1);
        menu.update("/c", true, 100, 24);
        assertEquals("/cmd01", menu.choice(false).value());
    }

    @Test
    void shortTerminalsShowFewerRows() {
        menu.update("/", true, 80, 6);
        assertEquals(5, lines().size(), "4 个候选与位置提示，加上输入行共 6 行");
        assertEquals("  1-4 / 15  ↑↓ 选择 · 滚轮滚动", lines().get(4));
        move(5);
        assertEquals("  /cmd02  命令 2", lines().getFirst());
    }

    @Test
    void positionLineIsTruncatedToTheTerminalWidth() {
        menu.update("/", true, 20, 24);
        assertEquals("  1-10 / 15  ↑↓ …", lines().get(10));
    }

    private void move(int times) {
        for (int i = 0; i < times; i++) {
            menu.move(1);
        }
    }

    private List<String> lines() {
        return List.of(menu.render().toString().split("\n"));
    }

    /** value 所在的行反显，相邻的行不反显。 */
    private void assertSelected(String value) {
        AttributedString shown = menu.render();
        String text = shown.toString();
        assertEquals(AttributedStyle.INVERSE, shown.styleAt(text.indexOf(value + " ")));
        assertEquals(AttributedStyle.DEFAULT, shown.styleAt(text.indexOf("/cmd10 ")));
    }
}
