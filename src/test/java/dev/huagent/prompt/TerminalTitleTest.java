package dev.huagent.prompt;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerminalTitleTest {
    private final List<String> titles = new ArrayList<>();
    private final TerminalTitle title = new TerminalTitle(titles::add, Path.of("work", "hu-agent"));
    private final PrintWriter output = new PrintWriter(new StringWriter());

    @Test
    void promptIsReadyAndInputIsWorking() {
        PromptStyle prompt = title.prompt(PromptStyle.plain());
        prompt.begin(output);
        prompt.end(output, "改一下标题");
        prompt.begin(output);
        assertEquals(List.of("✅ hu 就绪 · hu-agent", "⏳ hu 运行中 · hu-agent", "✅ hu 就绪 · hu-agent"), titles);
    }

    @Test
    void blankLinesAndEndOfInputKeepTheTitle() {
        PromptStyle prompt = title.prompt(PromptStyle.plain());
        prompt.begin(output);
        prompt.end(output, "  ");
        prompt.begin(output);
        prompt.end(output, null);
        assertEquals(List.of("✅ hu 就绪 · hu-agent"), titles);
    }

    @Test
    void readingWhileWorkingIsAskingUntilAnswered() throws Exception {
        PromptStyle prompt = title.prompt(PromptStyle.plain());
        BufferedReader input = title.input(new BufferedReader(new StringReader("执行 ls\ny\n")));
        prompt.begin(output);
        assertEquals("执行 ls", input.readLine());
        prompt.end(output, "执行 ls");
        // 模型运行中请求权限确认。
        assertEquals("y", input.readLine());
        assertEquals(List.of("✅ hu 就绪 · hu-agent", "⏳ hu 运行中 · hu-agent",
                "🔔 hu 待确认 · hu-agent", "⏳ hu 运行中 · hu-agent"), titles);
    }

    @Test
    void wrappedStyleStillDrawsThePrompt() {
        StringWriter text = new StringWriter();
        title.prompt(PromptStyle.plain()).begin(new PrintWriter(text, true));
        assertEquals("你> ", text.toString());
    }

    @Test
    void rootDirectoryShowsTheWholePath() {
        Path root = Path.of("").toAbsolutePath().getRoot();
        TerminalTitle rooted = new TerminalTitle(titles::add, root);
        assertEquals("✅ hu 就绪 · " + root, rooted.text(TerminalTitle.Status.READY));
    }
}
