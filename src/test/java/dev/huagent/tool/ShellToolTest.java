package dev.huagent.tool;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ShellToolTest {
    @TempDir
    Path directory;
    private final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

    @Test
    void runsInWorkingDirectoryAndCapturesChineseAndStderr() throws Exception {
        Path work = directory.resolve("练习");
        ShellTool shell = new ShellTool(work, Duration.ofSeconds(10), 12000);
        String command = windows
                ? "[Console]::WriteLine('中文输出'); [Console]::Error.WriteLine('错误输出'); "
                        + "[IO.File]::WriteAllText((Join-Path (Get-Location) 'proof.txt'), 'ok'); Get-Location"
                : "printf '中文输出\\n'; printf '错误输出\\n' >&2; printf ok > proof.txt; pwd";
        String result = shell.execute(arguments(command));
        assertTrue(result.contains("退出码：0"), result);
        assertTrue(result.contains("中文输出"), result);
        assertTrue(result.contains("错误输出"), result);
        assertTrue(result.contains("练习"), result);
        assertEquals("ok", Files.readString(work.resolve("proof.txt")));
    }

    @Test
    void reportsNonzeroExitCode() throws Exception {
        String result = shell(Duration.ofSeconds(10), 1000).execute(arguments("exit 7"));
        assertTrue(result.contains("退出码：7"), result);
    }

    @Test
    void reportsCommandFailure() throws Exception {
        String result = shell(Duration.ofSeconds(10), 1000)
                .execute(arguments("this_command_does_not_exist_12345"));
        assertTrue(result.contains("退出码："), result);
        assertFalse(result.contains("退出码：0"), result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "null", "[]", "{}", "{\"command\":3}", "{\"command\":\" \"}",
            "{\"command\":\"echo ok\",\"extra\":true}"})
    void rejectsInvalidArguments(String arguments) throws Exception {
        assertTrue(shell(Duration.ofSeconds(10), 1000).execute(arguments).contains("工具错误"));
    }

    @Test
    void boundsOutputWhileDrainingThePipe() {
        String command = windows ? "[Console]::Write(('x' * 200000))"
                : "i=0; while [ \"$i\" -lt 20000 ]; do printf xxxxxxxxxx; i=$((i+1)); done";
        String result = assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> shell(Duration.ofSeconds(10), 128).execute(arguments(command)));
        assertTrue(result.contains("退出码：0"), result);
        assertTrue(result.contains("输出已截断"), result);
        assertTrue(result.length() < 300, result);
    }

    @Test
    void truncationKeepsBothHeadAndTail() {
        // 构建和测试的结论在最后：截断时开头和结尾都要保留。
        String command = windows ? "[Console]::Write('BEGIN' + ('x' * 50000) + 'END')"
                : "printf BEGIN; i=0; while [ \"$i\" -lt 5000 ]; do printf xxxxxxxxxx; i=$((i+1)); done; printf END";
        String result = assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> shell(Duration.ofSeconds(10), 1000).execute(arguments(command)));
        assertTrue(result.startsWith("退出码：0\nBEGIN"), result);
        assertTrue(result.endsWith("xEND"), result);
        assertTrue(result.contains("[输出已截断：上限 1000 字符，省略了中间 49008 个字符；"), result);
    }

    @Test
    void timesOutAndStopsTheCommand() {
        String command = windows ? "Start-Sleep -Seconds 20; Set-Content late.txt done"
                : "sleep 20; printf done > late.txt";
        String result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> shell(Duration.ofMillis(300), 1000).execute(arguments(command)));
        assertTrue(result.contains("命令超时"), result);
        assertFalse(Files.exists(directory.resolve("late.txt")));
    }

    @Test
    void interruptionStopsWaiting() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                shell(Duration.ofSeconds(30), 1000)
                        .execute(arguments(windows ? "Start-Sleep -Seconds 20" : "sleep 20"));
            } catch (InterruptedException e) {
                interrupted.set(true);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        worker.start();
        Thread.sleep(300);
        worker.interrupt();
        worker.join(4000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertTrue(interrupted.get());
    }

    private ShellTool shell(Duration timeout, int limit) {
        return new ShellTool(directory, timeout, limit);
    }

    private String arguments(String command) {
        JSONObject json = new JSONObject();
        json.put("command", command);
        return json.toJSONString();
    }
}
