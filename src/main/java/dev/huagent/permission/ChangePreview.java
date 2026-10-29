package dev.huagent.permission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 权限确认时显示的改动预览（与 Claude Code 的确认界面相同）：edit_file / write_file 显示带行号的 diff，
 * 多行 Shell 命令逐行原样显示，代替挤成一行、换行被转义的 JSON 参数。只用于显示，不影响执行。
 * summary 跟在确认行的工具名后面，lines 是其下的明细。
 */
public record ChangePreview(String summary, List<Line> lines) {
    public enum Kind {
        CONTEXT, ADDED, REMOVED, NOTE, PLAIN
    }

    /** number 为 0 时不显示行号。 */
    public record Line(Kind kind, int number, String text) {
    }

    /** 最多显示的明细行数，其余折叠成一行说明。 */
    static final int MAX_LINES = 60;
    /** 改动前后保留的未改动行数。 */
    static final int CONTEXT_LINES = 2;
    /** 新建文件只显示开头这么多行。 */
    static final int NEW_FILE_LINES = 20;
    /** 逐行比对的规模上限（行数乘积）；超过时整段显示为删除加新增。 */
    static final long MAX_DIFF_CELLS = 1_000_000;
    private static final long MAX_FILE_BYTES = 1024 * 1024;

    public ChangePreview {
        lines = List.copyOf(lines);
    }

    /** old_text 在文件中的第一处替换成 new_text 后的样子，按整行显示；找不到时返回 null，由调用方退回显示参数。 */
    public static ChangePreview edit(String path, String content, String oldText, String newText, boolean replaceAll) {
        int index = oldText.isEmpty() ? -1 : content.indexOf(oldText);
        if (index < 0) {
            return null;
        }
        int count = 0;
        for (int at = index; at >= 0; at = content.indexOf(oldText, at + oldText.length())) {
            count++;
        }
        // 扩展到完整的行：从 old_text 所在行的行首，到它最后一个字符所在行的换行符之后；前后再各带上下文行。
        int lineStart = content.lastIndexOf('\n', index - 1) + 1;
        int end = index + oldText.length();
        int newline = content.indexOf('\n', end - 1);
        int lineEnd = newline < 0 ? content.length() : newline + 1;
        for (int k = 0; k < CONTEXT_LINES && lineStart > 0; k++) {
            lineStart = content.lastIndexOf('\n', lineStart - 2) + 1;
        }
        for (int k = 0; k < CONTEXT_LINES && lineEnd < content.length(); k++) {
            int next = content.indexOf('\n', lineEnd);
            lineEnd = next < 0 ? content.length() : next + 1;
        }
        String before = content.substring(lineStart, lineEnd);
        String after = content.substring(lineStart, index) + newText + content.substring(end, lineEnd);
        int firstLine = (int) content.substring(0, lineStart).chars().filter(c -> c == '\n').count() + 1;
        List<Line> lines = new ArrayList<>(diff(split(before), split(after), firstLine));
        if (count > 1) {
            lines.add(new Line(Kind.NOTE, 0, replaceAll ? "replace_all：共替换 " + count + " 处，只显示第一处"
                    : "old_text 在文件中出现了 " + count + " 次，工具会拒绝执行"));
        }
        return new ChangePreview(path + "（" + counts(lines) + "）", cap(lines));
    }

    /** existing 为 null 表示新建文件。 */
    public static ChangePreview write(String path, String existing, String content) {
        List<String> after = split(content);
        if (existing == null) {
            List<Line> lines = new ArrayList<>();
            for (int i = 0; i < Math.min(after.size(), NEW_FILE_LINES); i++) {
                lines.add(new Line(Kind.ADDED, i + 1, after.get(i)));
            }
            if (after.size() > NEW_FILE_LINES) {
                lines.add(new Line(Kind.NOTE, 0, "还有 " + (after.size() - NEW_FILE_LINES) + " 行未显示"));
            }
            return new ChangePreview(path + "（新建，" + after.size() + " 行）", lines);
        }
        if (existing.equals(content)) {
            return new ChangePreview(path + "（内容与现有文件相同）", List.of());
        }
        List<Line> lines = diff(split(existing), after, 1);
        return new ChangePreview(path + "（覆盖已有文件，" + counts(lines) + "）", cap(lines));
    }

    /** 多行 Shell 命令逐行显示；单行命令返回 null，确认行照常显示。 */
    public static ChangePreview command(String command) {
        if (command.indexOf('\n') < 0) {
            return null;
        }
        List<String> rows = split(command);
        List<Line> lines = new ArrayList<>();
        for (String row : rows) {
            lines.add(new Line(Kind.PLAIN, 0, row));
        }
        return new ChangePreview("多行命令，共 " + rows.size() + " 行", cap(lines));
    }

    /** 读取用于预览的文本文件；不存在返回 null，过大或读不了时抛出，调用方放弃预览。 */
    static String readText(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("文件过大，不生成预览");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** 先去掉相同的开头和结尾，中间逐行比对（最长公共子序列），再把远离改动的未改动行折叠。 */
    static List<Line> diff(List<String> before, List<String> after, int firstLine) {
        int prefix = 0;
        while (prefix < before.size() && prefix < after.size() && before.get(prefix).equals(after.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < before.size() - prefix && suffix < after.size() - prefix
                && before.get(before.size() - 1 - suffix).equals(after.get(after.size() - 1 - suffix))) {
            suffix++;
        }
        List<String> a = before.subList(prefix, before.size() - suffix);
        List<String> b = after.subList(prefix, after.size() - suffix);
        List<Line> all = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            all.add(new Line(Kind.CONTEXT, firstLine + i, before.get(i)));
        }
        int oldNo = firstLine + prefix;
        int newNo = firstLine + prefix;
        if ((long) a.size() * b.size() > MAX_DIFF_CELLS) {
            for (String line : a) {
                all.add(new Line(Kind.REMOVED, oldNo++, line));
            }
            for (String line : b) {
                all.add(new Line(Kind.ADDED, newNo++, line));
            }
        } else {
            int[][] lcs = new int[a.size() + 1][b.size() + 1];
            for (int i = a.size() - 1; i >= 0; i--) {
                for (int j = b.size() - 1; j >= 0; j--) {
                    lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1
                            : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
            int i = 0;
            int j = 0;
            while (i < a.size() || j < b.size()) {
                if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                    all.add(new Line(Kind.CONTEXT, newNo, a.get(i)));
                    i++;
                    j++;
                    oldNo++;
                    newNo++;
                } else if (i < a.size() && (j == b.size() || lcs[i + 1][j] >= lcs[i][j + 1])) {
                    // 与常见的 diff 一样，同一处改动先列删除的行，再列新增的行。
                    all.add(new Line(Kind.REMOVED, oldNo++, a.get(i++)));
                } else {
                    all.add(new Line(Kind.ADDED, newNo++, b.get(j++)));
                }
            }
        }
        for (int k = after.size() - suffix; k < after.size(); k++) {
            all.add(new Line(Kind.CONTEXT, newNo++, after.get(k)));
        }
        return collapse(all);
    }

    /** 只保留改动前后 CONTEXT_LINES 行未改动的内容，其余连续的未改动行折叠成一行说明。 */
    private static List<Line> collapse(List<Line> all) {
        boolean[] keep = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).kind() != Kind.CONTEXT) {
                for (int k = Math.max(0, i - CONTEXT_LINES); k <= Math.min(all.size() - 1, i + CONTEXT_LINES); k++) {
                    keep[k] = true;
                }
            }
        }
        List<Line> result = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < all.size(); i++) {
            if (keep[i]) {
                // 只提示两处改动之间折叠的行；第一处之前和最后一处之后的看行号就知道。
                if (skipped > 0 && !result.isEmpty()) {
                    result.add(new Line(Kind.NOTE, 0, "⋯ " + skipped + " 行未改动"));
                }
                skipped = 0;
                result.add(all.get(i));
            } else {
                skipped++;
            }
        }
        return result;
    }

    private static List<Line> cap(List<Line> lines) {
        if (lines.size() <= MAX_LINES) {
            return lines;
        }
        List<Line> result = new ArrayList<>(lines.subList(0, MAX_LINES));
        result.add(new Line(Kind.NOTE, 0, "还有 " + (lines.size() - MAX_LINES) + " 行未显示"));
        return result;
    }

    private static String counts(List<Line> lines) {
        long added = lines.stream().filter(line -> line.kind() == Kind.ADDED).count();
        long removed = lines.stream().filter(line -> line.kind() == Kind.REMOVED).count();
        return "+" + added + " −" + removed;
    }

    /** 按 \n 拆行并去掉行尾的 \r；结尾的换行不产生空行，空文本没有行。 */
    private static List<String> split(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return Arrays.stream(body.split("\n", -1)).map(line -> line.endsWith("\r")
                ? line.substring(0, line.length() - 1) : line).toList();
    }
}
