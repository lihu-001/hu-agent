package dev.huagent;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class HuMainTest {
    @Test
    void sessionFlagsCombineDebugAndAuto() {
        assertEquals(new HuMain.SessionFlags(false, true), HuMain.sessionFlags(new String[]{}), "默认非 debug、自动审批");
        assertEquals(new HuMain.SessionFlags(true, true), HuMain.sessionFlags(new String[]{"--debug"}));
        assertEquals(new HuMain.SessionFlags(true, true), HuMain.sessionFlags(new String[]{"-d"}));
        assertEquals(new HuMain.SessionFlags(false, true), HuMain.sessionFlags(new String[]{"--auto"}));
        assertEquals(new HuMain.SessionFlags(false, false), HuMain.sessionFlags(new String[]{"--no-auto"}));
        assertEquals(new HuMain.SessionFlags(true, false), HuMain.sessionFlags(new String[]{"--no-auto", "-d"}));
        assertEquals(new HuMain.SessionFlags(false, false), HuMain.sessionFlags(new String[]{"--auto", "--no-auto"}),
                "同时给出时以后一个为准");
        assertEquals(new HuMain.SessionFlags(false, true), HuMain.sessionFlags(new String[]{"--no-auto", "--auto"}));
        // 有其他参数时交给 options（版本、帮助或报错），不启动会话。
        assertNull(HuMain.sessionFlags(new String[]{"--debug", "x"}));
        assertNull(HuMain.sessionFlags(new String[]{"--version"}));
        assertNull(HuMain.sessionFlags(new String[]{"-s"}), "--auto 没有短选项");
    }

    @Test
    void unknownArgumentsStillFail() {
        StringWriter buffer = new StringWriter();
        assertEquals(2, HuMain.options(new String[]{"--debug", "x"}, new PrintWriter(buffer, true)));
        assertTrue(buffer.toString().contains("不支持的参数：--debug x"), buffer.toString());
        assertTrue(HuMain.usage().contains("-d, --debug"));
        assertTrue(HuMain.usage().contains("--auto"));
        assertTrue(HuMain.usage().contains("--no-auto"));
    }

    @Test
    void updateWithoutLauncherExplainsWhy() {
        StringWriter buffer = new StringWriter();
        assertEquals(2, HuMain.options(new String[]{"update", "--local"}, new PrintWriter(buffer, true)));
        assertTrue(buffer.toString().contains("update 由 hu 启动脚本执行"), buffer.toString());
        assertTrue(HuMain.usage().contains("hu update [--debug | -d] [--local | --pkg <安装包>]"));
    }
}
