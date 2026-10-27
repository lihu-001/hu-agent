package dev.huagent.hook;

import cn.hutool.core.codec.Base64;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 命令与模型输入分离：JSON 只进入 stdin，不拼接到 Shell 命令中。 */
final class HookCommandRunner {
    static final int MAX_OUTPUT_CHARS = 12000;

    record Result(int exitCode, String output) {
    }

    Result run(String command, Path cwd, String input, Duration timeout)
            throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Hook 执行线程已中断");
        }
        // 文件重定向仍是标准输入；即使命令完全不读 stdin，也不会堵塞写入线程。
        Path stdin = Files.createTempFile("java-agent-hook-", ".json");
        Process process = null;
        var reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "hook-output");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Files.writeString(stdin, input + "\n", StandardCharsets.UTF_8);
            process = new ProcessBuilder(commandLine(command))
                    .directory(cwd.toFile())
                    .redirectInput(stdin.toFile())
                    .redirectErrorStream(true)
                    .start();
            Process running = process;
            Future<String> output = reader.submit(() -> readOutput(running));
            long deadline = System.nanoTime() + timeout.toNanos();
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IOException("Hook 命令超时");
            }
            try {
                // 后台子进程可能持有管道，输出读取也受同一超时约束。
                String text = output.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                return new Result(process.exitValue(), text);
            } catch (TimeoutException e) {
                throw new IOException("Hook 输出读取超时", e);
            } catch (ExecutionException e) {
                throw new IOException("Hook 输出读取失败", e);
            }
        } finally {
            try {
                if (process != null) {
                    stopProcessTree(process);
                    process.getInputStream().close();
                }
            } finally {
                reader.shutdownNow();
                Files.deleteIfExists(stdin);
            }
        }
    }

    private String readOutput(Process process) throws IOException {
        try (Reader stream = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[2048];
            boolean truncated = false;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                int kept = Math.min(count, MAX_OUTPUT_CHARS - text.length());
                text.append(buffer, 0, kept);
                truncated |= kept < count;
            }
            return text + (truncated ? "\n[输出已截断，上限 " + MAX_OUTPUT_CHARS + " 字符]" : "");
        }
    }

    private List<String> commandLine(String command) {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return List.of("/bin/sh", "-c", command);
        }
        String script = """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
                [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
                $OutputEncoding = [Console]::OutputEncoding
                & {
                """ + command + """

                }
                if (-not $?) { exit 1 }
                if ($null -ne $LASTEXITCODE) { exit $LASTEXITCODE }
                """;
        return List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-OutputFormat", "Text", "-EncodedCommand",
                Base64.encode(script.getBytes(StandardCharsets.UTF_16LE)));
    }

    private void stopProcessTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) {
            descendants.get(i).destroyForcibly();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            // Windows 文件重定向句柄随进程释放，短暂等待后才能清理临时 stdin 文件。
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
