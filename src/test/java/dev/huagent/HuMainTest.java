package dev.huagent;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class HuMainTest {
    @Test
    void debugFlagStartsSessionInDebugMode() {
        assertTrue(HuMain.debugMode(new String[]{"--debug"}));
        assertTrue(HuMain.debugMode(new String[]{"-d"}));
        assertFalse(HuMain.debugMode(new String[]{}), "默认非 debug");
        assertFalse(HuMain.debugMode(new String[]{"--debug", "x"}));
    }

    @Test
    void unknownArgumentsStillFail() {
        StringWriter buffer = new StringWriter();
        assertEquals(2, HuMain.options(new String[]{"--debug", "x"}, new PrintWriter(buffer, true)));
        assertTrue(buffer.toString().contains("不支持的参数：--debug x"), buffer.toString());
        assertTrue(HuMain.usage().contains("-d, --debug"));
    }

    @Test
    void updateWithoutLauncherExplainsWhy() {
        StringWriter buffer = new StringWriter();
        assertEquals(2, HuMain.options(new String[]{"update", "--local"}, new PrintWriter(buffer, true)));
        assertTrue(buffer.toString().contains("update 由 hu 启动脚本执行"), buffer.toString());
        assertTrue(HuMain.usage().contains("hu update [--local]"));
    }
}
