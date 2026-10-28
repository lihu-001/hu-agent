package dev.huagent.prompt;

import dev.huagent.command.CommandResult;
import dev.huagent.command.SlashCommand;
import org.jline.reader.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlashCompleterTest {
    private static final List<SlashCommand> COMMANDS = List.of(
            SlashCommand.local("help", "列出全部斜杠命令", (a, c) -> CommandResult.print("")),
            SlashCommand.local("compact", "立即压缩", (a, c) -> CommandResult.print("")),
            SlashCommand.local("context", "查看上下文用量", (a, c) -> CommandResult.print("")),
            SlashCommand.local("exit", "退出会话", (a, c) -> CommandResult.exit()).withAliases("quit"),
            SlashCommand.prompt("review", "/review [范围]", "审查", (a, c) -> CommandResult.print("")));

    private final SlashCompleter completer = new SlashCompleter(() -> COMMANDS);

    @Test
    void commandChoicesAreListedAfterSpaceAndFilteredBySubstring() {
        SlashCommand model = SlashCommand.localWithArgument("model", "/model [模型名称]", "切换模型",
                (a, c) -> CommandResult.print("")).withChoices(() -> List.of(
                new SlashCommand.Choice("gpt-6-astra", "当前"), new SlashCommand.Choice("glm-5.3-flash", "常用"),
                new SlashCommand.Choice("cc/GLM-5.3", "")));
        SlashCompleter models = new SlashCompleter(() -> List.of(model));

        assertEquals(List.of("/model gpt-6-astra", "/model glm-5.3-flash", "/model cc/GLM-5.3"),
                values(models.candidates("/model ", 0)));
        assertEquals(List.of("/model glm-5.3-flash", "/model cc/GLM-5.3"), values(models.candidates("/model  glm", 0)));
        assertEquals(List.of(), models.candidates("/model zzz", 0));
        assertEquals(List.of(), models.candidates("/model glm extra", 0));
        assertEquals(List.of("/model"), values(models.candidates("/mo", 0)), "命令名阶段照常补全命令");

        List<Candidate> found = models.candidates("/model ", 0);
        assertEquals("gpt-6-astra    当前", found.get(0).displ());
        assertEquals("cc/GLM-5.3", found.get(2).displ());
        assertFalse(found.get(0).complete(), "选中后不补空格，再回车执行");
        // 列数 20：缩进余量 4，只剩 16 列，说明被截断。
        assertEquals("glm-5.3-flash  …", models.candidates("/model glm-5", 20).get(0).displ());
    }

    @Test
    void slashAloneListsEveryNameAndAlias() {
        assertEquals(List.of("/help", "/compact", "/context", "/exit", "/quit", "/review"),
                values(completer.candidates("/", 0)));
    }

    @Test
    void prefixFiltersByNameAndAlias() {
        assertEquals(List.of("/compact", "/context"), values(completer.candidates("/co", 0)));
        assertEquals(List.of("/quit"), values(completer.candidates("/q", 0)));
        assertEquals(List.of(), values(completer.candidates("/zz", 0)));
    }

    @Test
    void onlyACommandNameAtLineStartIsCompleted() {
        for (String typed : List.of("", "hello", " /co", "/compact ", "/compact 保留", "/usr/bin/env", "/Co", "看 /co")) {
            assertEquals(List.of(), completer.candidates(typed, 0), typed);
        }
    }

    @Test
    void displayAlignsNamesAndAppendsDescriptions() {
        List<Candidate> found = completer.candidates("/co", 0);
        assertEquals("/compact  立即压缩", found.get(0).displ());
        assertEquals("/context  查看上下文用量", found.get(1).displ());
        assertTrue(found.get(0).complete(), "选中后补空格，可以接着输入参数");
        assertEquals("/quit  退出会话（/exit 的别名）", completer.candidates("/q", 0).get(0).displ());
    }

    @Test
    void longDescriptionsAreTruncatedToTerminalWidth() {
        // 列数 24：缩进余量 4 + 名称 8 + 间隔 2，说明只剩 10 列（5 个汉字，最后一列留给 …）。
        assertEquals("/context  查看上下…", completer.candidates("/cont", 24).get(0).displ());
        assertEquals("/context  查看上下文用量", completer.candidates("/cont", 80).get(0).displ());
    }

    @Test
    void truncateCountsWideCharactersAsTwoColumns() {
        assertEquals("abc", SlashCompleter.truncate("abc", 3));
        assertEquals("ab…", SlashCompleter.truncate("abcd", 3));
        assertEquals("中…", SlashCompleter.truncate("中文说明", 4));
        assertEquals("", SlashCompleter.truncate("中文", 0));
    }

    private static List<String> values(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::value).toList();
    }
}
