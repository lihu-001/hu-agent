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
    void printFlagsTakeTaskAndSessionOptions() {
        assertNull(HuMain.printFlags(new String[]{"--version"}), "没有 -p 时交给 options");
        assertNull(HuMain.printFlags(new String[]{"修复测试"}));
        assertEquals(new HuMain.PrintFlags("修复测试", false, true), HuMain.printFlags(new String[]{"-p", "修复测试"}));
        assertEquals(new HuMain.PrintFlags("修复 失败的 测试", true, false),
                HuMain.printFlags(new String[]{"修复", "--no-auto", "失败的", "--print", "测试", "-d"}),
                "选项可以在任意位置，任务参数以空格连接");
        assertEquals(new HuMain.PrintFlags("-d 是什么选项", false, true),
                HuMain.printFlags(new String[]{"-p", "--", "-d", "是什么选项"}), "-- 之后都是任务文字");
        assertEquals(new HuMain.PrintFlags("说明 -p", false, true),
                HuMain.printFlags(new String[]{"--print", "--", "说明", "-p"}));
        assertEquals(new HuMain.PrintFlags(null, false, true), HuMain.printFlags(new String[]{"-p"}), "从标准输入读取");
        assertEquals(new HuMain.PrintFlags(null, true, true), HuMain.printFlags(new String[]{"-p", "-", "-d"}));
        assertEquals(new HuMain.PrintFlags("", false, true), HuMain.printFlags(new String[]{"-p", ""}),
                "给了空任务不读标准输入，由 print 报没有任务");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HuMain.printFlags(new String[]{"-p", "任务", "-v"}));
        assertEquals("-p 不支持的参数：-v", error.getMessage());
        assertTrue(HuMain.usage().contains("hu -p [-d] [--no-auto] [--] <任务>"));
        assertTrue(HuMain.usage().contains("-p, --print"));
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
        assertTrue(HuMain.usage().contains("hu update [--debug | -d] [--local [<源码目录>] | --pkg <安装包>]"));
    }
}
