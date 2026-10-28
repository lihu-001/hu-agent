package dev.huagent.prompt;

import org.jline.reader.Candidate;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.List;
import java.util.Objects;

/**
 * 输入行下方的斜杠命令列表（LineEditor 把它画在 JLine 的 post 区域）：光标在行尾、整行是 "/命令名前缀" 时显示，
 * 最多 ROWS 个候选，更多的靠滚动查看，此时末尾一行提示当前位置。
 * ↑↓ 移动选中项，列表跟着滚动，越过两端绕回；滚轮只滚动列表，选中项被滚出视野时停在边缘。
 * 输入一变就重新过滤，选中项与滚动位置复位。
 */
final class SlashMenu {
    /** 列表最多显示的候选数。 */
    static final int ROWS = 10;
    /** 与 SlashCompleter.MARGIN 配合：缩进两列，行尾再留两列。 */
    private static final String INDENT = "  ";

    private final SlashCompleter completer;
    /** 上一次 update 的输入与列数，都没变时沿用候选与选中项。 */
    private String typed = "";
    private int columns;
    private List<Candidate> candidates = List.of();
    private int rows = ROWS;
    /** 选中项在 candidates 中的下标，-1 为未选中。 */
    private int selected = -1;
    /** 列表第一行对应的下标。 */
    private int top;

    SlashMenu(SlashCompleter completer) {
        this.completer = Objects.requireNonNull(completer);
    }

    /**
     * 按当前输入刷新，返回列表是否显示。line 是整行输入，atEnd 表示光标在行尾，不在行尾时不显示；
     * columns、height 是终端列数与行数，不为正时视为不限。
     */
    boolean update(String line, boolean atEnd, int columns, int height) {
        String now = atEnd ? line : "";
        boolean changed = !now.equals(typed);
        if (changed) {
            selected = -1;
            top = 0;
        }
        if (changed || columns != this.columns) {
            typed = now;
            this.columns = columns;
            candidates = completer.candidates(now, columns);
        }
        // 留出输入行与位置提示行，矮终端里列表不把输入行顶出屏幕。
        rows = height > 0 ? Math.max(1, Math.min(ROWS, height - 2)) : ROWS;
        scrollTo(top);
        reveal();
        return visible();
    }

    boolean visible() {
        return !candidates.isEmpty();
    }

    /** ↑↓：选中项移动 step 个，越过两端绕回；未选中时 ↓ 选中可见的第一行，↑ 选中可见的最后一行。 */
    void move(int step) {
        int count = candidates.size();
        if (count == 0) {
            return;
        }
        if (selected < 0) {
            selected = step > 0 ? top : Math.min(count, top + rows) - 1;
        } else {
            selected = Math.floorMod(selected + step, count);
        }
        reveal();
    }

    /** 滚轮：列表滚动 step 行，不越过两端；选中项被滚出视野时停在边缘。 */
    void scroll(int step) {
        scrollTo(top + step);
        if (selected >= 0) {
            selected = Math.clamp(selected, top, top + rows - 1);
        }
    }

    /** 选中的候选；未选中时 orFirst 为 true 取第一个，否则为 null。列表不显示时为 null。 */
    Candidate choice(boolean orFirst) {
        if (selected >= 0) {
            return candidates.get(selected);
        }
        return orFirst && visible() ? candidates.getFirst() : null;
    }

    /** 列表的显示内容，选中行反显。 */
    AttributedString render() {
        AttributedStringBuilder out = new AttributedStringBuilder();
        int end = Math.min(candidates.size(), top + rows);
        for (int i = top; i < end; i++) {
            if (i > top) {
                out.append('\n');
            }
            out.append(INDENT);
            out.styled(i == selected ? AttributedStyle.INVERSE : AttributedStyle.DEFAULT, candidates.get(i).displ());
        }
        if (candidates.size() > rows) {
            String position = (top + 1) + "-" + end + " / " + candidates.size() + "  ↑↓ 选择 · 滚轮滚动";
            out.append('\n').append(INDENT).styled(AttributedStyle.DEFAULT.faint(),
                    columns > 0 ? SlashCompleter.truncate(position, columns - SlashCompleter.MARGIN) : position);
        }
        return out.toAttributedString();
    }

    private void scrollTo(int index) {
        top = Math.max(0, Math.min(index, candidates.size() - rows));
    }

    /** 滚动到选中项可见。 */
    private void reveal() {
        if (selected >= 0) {
            scrollTo(Math.clamp(top, selected - rows + 1, selected));
        }
    }
}
