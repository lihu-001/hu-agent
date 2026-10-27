package dev.huagent.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TerminalWidthTest {
    @Test
    void columnsVariableWinsWithoutStartingProcess() {
        assertEquals(132, new TerminalWidth(Map.of("COLUMNS", "132"), true).getAsInt());
        assertEquals(90, new TerminalWidth(Map.of("COLUMNS", " 90 "), false).getAsInt());
    }

    @Test
    void parsesModeConInAnyLanguage() {
        String english = """

                Status for device CON:
                ----------------------
                    Lines:          9001
                    Columns:        120
                    Keyboard rate:  31
                    Keyboard delay: 1
                    Code page:      936
                """;
        String chinese = """

                设备状态 CON:
                ---------
                    行:　       30
                    列:　       156
                    键盘速率:   31
                    键盘延迟:   1
                    代码页:     936
                """;
        assertEquals(120, TerminalWidth.parseModeCon(english));
        assertEquals(156, TerminalWidth.parseModeCon(chinese));
        assertEquals(-1, TerminalWidth.parseModeCon("Status for device CON:\n    Lines: 30\n"));
    }

    @Test
    void parsesSttySizeAndRejectsImplausibleWidths() {
        assertEquals(100, TerminalWidth.parseStty("24 100\n"));
        assertEquals(-1, TerminalWidth.parseStty(""));
        assertEquals(-1, TerminalWidth.parseStty("24 0"));
        assertEquals(-1, TerminalWidth.parseStty("24 abc"));
    }
}
