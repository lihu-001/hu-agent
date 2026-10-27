package dev.huagent.command;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandLineTest {

    @Test
    void parsesNameAndStrippedArgument() {
        assertEquals(new CommandLine("help", ""), CommandLine.parse("/help"));
        assertEquals(new CommandLine("help", ""), CommandLine.parse("  /help  "));
        assertEquals(new CommandLine("compact", "保留 文件名"), CommandLine.parse("/compact   保留 文件名  "));
        assertEquals(new CommandLine("code-review", "src/Foo.java"), CommandLine.parse("/code-review src/Foo.java"));
        assertEquals(new CommandLine("s3", "x"), CommandLine.parse("/s3 x"));
        // 中文输入法常见的全角空格也算分隔。
        assertEquals(new CommandLine("goal", "测试通过"), CommandLine.parse("/goal　测试通过"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "help", "/", "/ help", "//help", "/Help", "/1abc", "/-x", "/usr/bin/env",
            "/skills/", "/goal:x", "请运行 /help", "/中文"})
    void otherTextIsNotCommandForm(String line) {
        assertNull(CommandLine.parse(line), line);
    }

    @Test
    void nullIsNotCommand() {
        assertNull(CommandLine.parse(null));
    }

    @Test
    void validNamesMatchCommandForm() {
        assertTrue(CommandLine.isValidName("release-notes"));
        assertTrue(CommandLine.isValidName("a1"));
        assertFalse(CommandLine.isValidName("1a"));
        assertFalse(CommandLine.isValidName("A"));
        assertFalse(CommandLine.isValidName("a b"));
        assertFalse(CommandLine.isValidName(""));
    }
}
