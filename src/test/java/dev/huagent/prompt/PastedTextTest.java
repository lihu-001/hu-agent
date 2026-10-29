package dev.huagent.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PastedTextTest {
    private final PastedText pasted = new PastedText();

    @Test
    void multiLinePasteBecomesAPlaceholderAndExpandsBack() {
        // Windows 的 \r\n 与单独的 \r 都换成 \n，末尾的换行去掉，不算行数。
        String placeholder = pasted.insert("第一行\r\n第二行\r第三行\n\n");
        assertEquals("[粘贴文本 #1，共 3 行]", placeholder);
        assertEquals("看看 第一行\n第二行\n第三行 这段", pasted.expand("看看 " + placeholder + " 这段"));
    }

    @Test
    void shortSingleLineIsInsertedAsIs() {
        assertEquals("ls -la ", pasted.insert("ls -la \n"));
        assertEquals("x".repeat(PastedText.LONG), pasted.insert("x".repeat(PastedText.LONG)));
        assertEquals("[粘贴文本 #1]", pasted.expand("[粘贴文本 #1]"));
    }

    @Test
    void longSingleLineCountsCharacters() {
        String text = "吧".repeat(PastedText.LONG + 1);
        assertEquals("[粘贴文本 #1，共 801 字符]", pasted.insert(text));
        assertEquals(text, pasted.expand("[粘贴文本 #1，共 801 字符]"));
    }

    @Test
    void onlyRegisteredPlaceholdersExpandAndOnlyOnce() {
        String first = pasted.insert("a\nb");
        // 原文里有占位样式的文字（包括别的粘贴的占位）时原样保留，不再展开；替换文字里的 $ 与 \ 也原样保留。
        String second = pasted.insert("$1 \\ " + first + "\nc");
        assertEquals("[粘贴文本 #2，共 2 行]", second);
        assertEquals("$1 \\ [粘贴文本 #1，共 2 行]\nc [粘贴文本 #9，共 2 行]", pasted.expand(second + " [粘贴文本 #9，共 2 行]"));
        assertNull(pasted.expand(null));
    }

    @Test
    void placeholderBeforeTheCursorIsDeletedWhole() {
        String placeholder = pasted.insert("a\nb");
        assertEquals(placeholder.length(), pasted.endingIn("看 " + placeholder));
        assertEquals(0, pasted.endingIn(placeholder + " "));
        assertEquals(0, pasted.endingIn("[粘贴文本 #2，共 2 行]"));
    }
}
