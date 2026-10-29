package dev.huagent.prompt;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 粘贴进输入行的长文本与 Claude Code 一样折叠成一行占位，如 "[粘贴文本 #1，共 12 行]"，提交时换回原文：
 * 输入行不再逐行重画整段文字，粘贴大段日志或代码也不卡。多行或超过 LONG 个字符的粘贴折叠，较短的单行照常插入。
 * 原文在整个会话里保留，↑ 翻出的历史、Esc 清掉又找回的输入里的占位同样换回原文。只在读取输入的线程上使用。
 */
final class PastedText {
    /** 单行粘贴超过这么多字符时也折叠。 */
    static final int LONG = 800;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[粘贴文本 #\\d+，共 \\d+ (?:行|字符)]");

    /** 占位 → 原文。 */
    private final Map<String, String> texts = new HashMap<>();
    private int next = 1;

    /** 换行统一为 \n 并去掉末尾的换行，返回要插入输入行的文字：需要折叠时登记原文、返回占位，否则为原文。 */
    String insert(String pasted) {
        String text = pasted.replace("\r\n", "\n").replace('\r', '\n');
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        text = text.substring(0, end);
        long lines = text.chars().filter(c -> c == '\n').count() + 1;
        int length = text.codePointCount(0, text.length());
        if (lines == 1 && length <= LONG) {
            return text;
        }
        String placeholder = "[粘贴文本 #" + next++ + "，共 " + (lines > 1 ? lines + " 行" : length + " 字符") + "]";
        texts.put(placeholder, text);
        return placeholder;
    }

    /** 把 line 里登记过的占位换回原文；原文里碰巧有占位样式的文字时不再展开。 */
    String expand(String line) {
        if (line == null || texts.isEmpty()) {
            return line;
        }
        return PLACEHOLDER.matcher(line)
                .replaceAll(match -> Matcher.quoteReplacement(texts.getOrDefault(match.group(), match.group())));
    }

    /** text 以登记过的占位结尾时返回占位的长度，否则为 0：退格时整个删掉占位。 */
    int endingIn(String text) {
        if (!text.endsWith("]")) {
            return 0;
        }
        for (String placeholder : texts.keySet()) {
            if (text.endsWith(placeholder)) {
                return placeholder.length();
            }
        }
        return 0;
    }
}
