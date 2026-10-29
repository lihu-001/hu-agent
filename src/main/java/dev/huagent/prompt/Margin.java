package dev.huagent.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * hu 交互终端对话区的左边距与折行，与 Claude Code 相同：除段首的 bullet（●）外，内容不贴着终端最左列。
 * LineEditor 在任务期间与读取期间（其他线程插到输入行上方的输出）对每一行套用（见 LineEditor.margin）：
 * 顶格的行（后台通知、【自动批准】记录、提示与失败说明等）前面补两列；超过终端宽度的行在这里折开，
 * 续行与该行的正文对齐（bullet 之后、⎿ 之后、行首空格之后），不会被终端折到最左列。
 * 英文按词折行，中文每个字前后都能折开，一个词一整行也放不下时按列硬折；
 * 宽度按 FramedPrompt.displayWidth 计算，ANSI 控制序列不占列。
 */
public final class Margin {
    static final String INDENT = "  ";
    /** 续行留给正文的列数少于这个值时不折行，交给终端。 */
    private static final int MIN_TEXT = 10;
    private static final int TAB = 8;
    private static final char ESC = '\u001b';

    private final String bullet;
    private final String branch;

    /** bullet 与 branch 是 Transcript 段首与结果行的符号（● 与 ⎿，或 * 与 -）。 */
    public Margin(String bullet, String branch) {
        this.bullet = Objects.requireNonNull(bullet);
        this.branch = Objects.requireNonNull(branch);
    }

    /**
     * text 是要输出的若干行，最后一行可以没有换行（提示语）；columns 是终端列数，每行最多用到 columns - 1 列，与状态栏相同。
     * 换行符保持原样（\r\n 或 \n），折出的续行沿用所在行的换行符，没有换行的最后一行用 \n。
     */
    public String apply(String text, int columns) {
        StringBuilder out = new StringBuilder();
        int start = 0;
        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline;
            int content = end > start && text.charAt(end - 1) == '\r' ? end - 1 : end;
            String separator = newline < 0 ? "\n" : text.substring(content, end + 1);
            out.append(String.join(separator, layout(text.substring(start, content), columns - 1)));
            if (newline < 0) {
                break;
            }
            out.append(separator);
            start = newline + 1;
        }
        return out.toString();
    }

    /** 一行（不含换行符）：顶格且不是段首时补上边距，超过 width 列时折开。 */
    List<String> layout(String line, int width) {
        String visible = strip(line);
        if (visible.isBlank()) {
            return List.of(line);
        }
        int lead = 0;
        while (visible.charAt(lead) == ' ') {
            lead++;
        }
        String shown = line;
        int indent;
        if (lead == 0 && visible.startsWith(bullet + " ")) {
            indent = FramedPrompt.displayWidth(bullet) + 1;
        } else if (lead == 0) {
            shown = INDENT + line;
            indent = INDENT.length();
        } else if (visible.startsWith(branch + "  ", lead)) {
            indent = lead + FramedPrompt.displayWidth(branch) + 2;
        } else {
            indent = lead;
        }
        if (width - indent < MIN_TEXT || width(shown) <= width) {
            return List.of(shown);
        }
        // 切成空格、中文等宽字符（前后都能折开）和词（连续的其他字符，含其中的控制序列）依次排进各行。
        Rows rows = new Rows(width, indent);
        for (int i = 0; i < shown.length(); ) {
            int end = i;
            char c = shown.charAt(i);
            if (c == ' ' || c == '\t') {
                while (end < shown.length() && (shown.charAt(end) == ' ' || shown.charAt(end) == '\t')) {
                    end++;
                }
                rows.spaces(shown.substring(i, end));
            } else if (columns(shown.codePointAt(i)) > 1) {
                end += Character.charCount(shown.codePointAt(i));
                rows.word(shown.substring(i, end));
            } else {
                while (end < shown.length()) {
                    int sequence = escapeEnd(shown, end);
                    if (sequence > end) {
                        end = sequence;
                        continue;
                    }
                    int code = shown.codePointAt(end);
                    if (code == ' ' || code == '\t' || columns(code) > 1) {
                        break;
                    }
                    end += Character.charCount(code);
                }
                rows.word(shown.substring(i, end));
            }
            i = end;
        }
        return rows.finish();
    }

    /** 把空格和词排进宽 width 列的行，续行以 indent 个空格开头。 */
    private static final class Rows {
        private final int width;
        private final String pad;
        private final List<String> rows = new ArrayList<>();
        private final StringBuilder row = new StringBuilder();
        private int used;
        /** 行尾连续空格在 row 中开始的位置，换行时去掉；-1 表示行尾不是空格。 */
        private int trailing = -1;
        /** 行末放不下的空格：丢掉，下一个词换到新行；之后没有词时也不留空行。 */
        private boolean pending;

        Rows(int width, int indent) {
            this.width = width;
            this.pad = " ".repeat(indent);
        }

        /** 制表符按 8 列对齐展开成空格，续行的缩进不会打乱它的宽度。 */
        void spaces(String run) {
            for (int i = 0; i < run.length() && !pending; i++) {
                int columns = run.charAt(i) == '\t' ? TAB - used % TAB : 1;
                if (used + columns > width) {
                    pending = true;
                } else {
                    if (trailing < 0) {
                        trailing = row.length();
                    }
                    row.append(" ".repeat(columns));
                    used += columns;
                }
            }
        }

        /** 放得下就接在当前行；新的一行放得下就换行；一行也放不下的长词接着当前行按列硬折。 */
        void word(String text) {
            int columns = width(text);
            if (columns == 0) {
                // 只有控制序列（如行尾的颜色复位）：不占列，不换行。
                row.append(text);
                return;
            }
            if (!pending && used + columns <= width) {
                append(text, columns);
                return;
            }
            if (pad.length() + columns <= width) {
                newRow();
                append(text, columns);
                return;
            }
            if (pending) {
                newRow();
            }
            for (int i = 0; i < text.length(); ) {
                int sequence = escapeEnd(text, i);
                if (sequence > i) {
                    row.append(text, i, sequence);
                    i = sequence;
                    continue;
                }
                int code = text.codePointAt(i);
                i += Character.charCount(code);
                if (used + columns(code) > width) {
                    newRow();
                }
                append(Character.toString(code), columns(code));
            }
        }

        private void append(String text, int columns) {
            row.append(text);
            used += columns;
            trailing = -1;
        }

        private void newRow() {
            rows.add(trailing < 0 ? row.toString() : row.substring(0, trailing));
            row.setLength(0);
            row.append(pad);
            used = pad.length();
            trailing = -1;
            pending = false;
        }

        List<String> finish() {
            rows.add(row.toString());
            return rows;
        }
    }

    /** 显示列数：控制序列不占列，制表符对齐到下一个 8 列，其余控制字符不占列。 */
    static int width(String text) {
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int sequence = escapeEnd(text, i);
            if (sequence > i) {
                i = sequence;
                continue;
            }
            int code = text.codePointAt(i);
            i += Character.charCount(code);
            used += code == '\t' ? TAB - used % TAB : columns(code);
        }
        return used;
    }

    private static int columns(int code) {
        return Character.isISOControl(code) ? 0 : FramedPrompt.displayWidth(Character.toString(code));
    }

    /** 去掉控制序列后的文字，用来判断行首是 bullet、⎿ 还是空格。 */
    private static String strip(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int sequence = escapeEnd(text, i);
            if (sequence > i) {
                i = sequence;
            } else {
                out.append(text.charAt(i++));
            }
        }
        return out.toString();
    }

    /** i 处是 ESC 开头的控制序列（CSI 如颜色、OSC 如标题、其余两字符序列）时返回它的结束位置，否则返回 i。 */
    private static int escapeEnd(String text, int i) {
        if (text.charAt(i) != ESC) {
            return i;
        }
        if (i + 1 >= text.length()) {
            return text.length();
        }
        char kind = text.charAt(i + 1);
        int j = i + 2;
        if (kind == '[') {
            while (j < text.length() && (text.charAt(j) < 0x40 || text.charAt(j) > 0x7e)) {
                j++;
            }
            return Math.min(j + 1, text.length());
        }
        if (kind == ']') {
            while (j < text.length() && text.charAt(j) != '\u0007'
                    && !(text.charAt(j) == ESC && j + 1 < text.length() && text.charAt(j + 1) == '\\')) {
                j++;
            }
            return j >= text.length() ? j : Math.min(j + (text.charAt(j) == ESC ? 2 : 1), text.length());
        }
        return j;
    }
}
