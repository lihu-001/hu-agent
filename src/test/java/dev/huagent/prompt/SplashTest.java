package dev.huagent.prompt;

import dev.huagent.prompt.Splash.ColorSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SplashTest {
    private static final String TIP = "/compact 立即压缩上下文";

    @Test
    void centersLogoNameVersionAndTip() {
        List<String> lines = Splash.render("1.2.3", TIP, 81, true, ColorSupport.NONE).lines().toList();
        // 前后空行、六行像素字、空行、提示。
        assertEquals(10, lines.size(), String.join("\n", lines));
        assertEquals("", lines.get(0));
        // 宽 80：像素字 14 列 + 间隔 4 + 名称 8 = 26，左边留 27 列。
        String pad = " ".repeat(27);
        assertEquals(pad + "██", lines.get(1));
        assertEquals(pad + "██████  ██  ██    hu-agent", lines.get(3));
        assertEquals(pad + "██  ██  ██  ██    v1.2.3", lines.get(4));
        assertEquals(pad + "██  ██  ██████", lines.get(6));
        String tip = "提示：" + TIP;
        assertEquals(" ".repeat((80 - FramedPrompt.displayWidth(tip)) / 2) + tip, lines.get(8));
        assertEquals("", lines.get(9));
    }

    @Test
    void devBuildHasNoVPrefix() {
        String text = Splash.render("dev", TIP, 80, true, ColorSupport.NONE);
        assertTrue(text.contains("    dev\n"), text);
        assertFalse(text.contains("vdev"), text);
    }

    @Test
    void narrowTerminalFallsBackToOneLine() {
        String text = Splash.render("1.2.3", TIP, 26, true, ColorSupport.NONE);
        assertFalse(text.contains("█"), text);
        assertTrue(text.contains("\nhu-agent v1.2.3\n"), text);
        // 提示放不下时不加左边距，交给终端折行。
        assertTrue(text.contains("\n提示：" + TIP + "\n"), text);
    }

    @Test
    void asciiPixelsWhenBlocksUnavailable() {
        String text = Splash.render("1.2.3", TIP, 80, false, ColorSupport.NONE);
        assertFalse(text.contains("█"), text);
        assertTrue(text.contains("######  ##  ##    hu-agent"), text);
    }

    @Test
    void colorsKeepLayoutAndResetEverySegment() {
        String plain = Splash.render("1.2.3", TIP, 81, true, ColorSupport.NONE);
        for (ColorSupport color : List.of(ColorSupport.TRUECOLOR, ColorSupport.PALETTE_256)) {
            String text = Splash.render("1.2.3", TIP, 81, true, color);
            assertTrue(text.contains(color == ColorSupport.TRUECOLOR ? "\u001b[38;2;" : "\u001b[38;5;"), text);
            assertEquals(plain, text.replaceAll("\u001b\\[[0-9;]*m", ""));
            for (String line : text.lines().toList()) {
                assertTrue(!line.contains("\u001b") || line.endsWith("\u001b[0m"), line);
            }
        }
    }

    @Test
    void gradientRunsFromOrangeThroughAmberToGold() {
        assertEquals(0xEA580C, Splash.gradient(0));
        assertEquals(0xF59E0B, Splash.gradient(0.5));
        assertEquals(0xFACC15, Splash.gradient(1));
        assertEquals(0xFACC15, Splash.gradient(1.5), "超出范围时取端点");
    }

    @Test
    void everyTipFitsAnEightyColumnTerminal() {
        for (String tip : Splash.TIPS) {
            assertTrue(FramedPrompt.displayWidth("提示：" + tip) <= 79, tip);
        }
    }

    @Test
    void detectsColorSupportFromEnvironment() {
        assertEquals(ColorSupport.PALETTE_256, ColorSupport.detect(Map.of("TERM", "xterm-256color"), false));
        assertEquals(ColorSupport.TRUECOLOR,
                ColorSupport.detect(Map.of("TERM", "xterm-256color", "COLORTERM", "truecolor"), false));
        assertEquals(ColorSupport.NONE, ColorSupport.detect(Map.of(), false), "没有 TERM");
        assertEquals(ColorSupport.NONE, ColorSupport.detect(Map.of("TERM", "dumb"), false));
        assertEquals(ColorSupport.NONE, ColorSupport.detect(Map.of("TERM", "xterm", "NO_COLOR", "1"), false));
        assertEquals(ColorSupport.PALETTE_256, ColorSupport.detect(Map.of("TERM", "xterm", "NO_COLOR", ""), false),
                "NO_COLOR 为空时不生效");

        assertEquals(ColorSupport.NONE, ColorSupport.detect(Map.of(), true), "传统控制台窗口");
        assertEquals(ColorSupport.NONE, ColorSupport.detect(Map.of("COLORTERM", "truecolor"), true));
        assertEquals(ColorSupport.TRUECOLOR, ColorSupport.detect(Map.of("WT_SESSION", "x"), true));
        assertEquals(ColorSupport.TRUECOLOR,
                ColorSupport.detect(Map.of("TERM_PROGRAM", "vscode", "COLORTERM", "truecolor"), true));
        assertEquals(ColorSupport.PALETTE_256, ColorSupport.detect(Map.of("ConEmuANSI", "ON"), true));
        assertEquals(ColorSupport.NONE, ColorSupport.detect(Map.of("WT_SESSION", "x", "NO_COLOR", "1"), true));
    }
}
