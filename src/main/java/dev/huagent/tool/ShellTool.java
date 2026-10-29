package dev.huagent.tool;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class ShellTool implements AgentTool {
    private final Path workDirectory;
    private final Duration timeout;
    private final int maxOutputChars;
    private final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

    public ShellTool(Path workDirectory, Duration timeout, int maxOutputChars) {
        if (timeout.toMillis() <= 0 || maxOutputChars <= 0) {
            throw new IllegalArgumentException("命令超时和输出上限必须为正数");
        }
        this.workDirectory = workDirectory.toAbsolutePath().normalize();
        this.timeout = timeout;
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public ToolDefinition definition() {
        JSONObject parameters = JSON.parseObject("""
                {"type":"object","properties":{"command":{"type":"string","description":"要执行的命令"}},
                 "required":["command"],"additionalProperties":false}
                """);
        return new ToolDefinition("shell",
                "在 " + workDirectory + " 中执行命令。语法为 "
                        + (windows ? "Windows PowerShell 5.1：没有 && 和 ||，用 ; 分隔，需要前一条成功时写 "
                        + "if ($?) { ... }；没有 grep、head、tail、cat，对应 Select-String、Select-Object -First/-Last、"
                        + "Get-Content" : "POSIX /bin/sh")
                        + "。每次启动独立进程，cd 和变量不会跨调用保留。命令超时 "
                        + timeout.toSeconds() + " 秒，最多返回 " + maxOutputChars
                        + " 个输出字符，超出时保留开头和结尾。用于构建、测试、git 等命令；"
                        + "查找文件、搜索内容和读文件改用 glob、grep、read_file，不要用 shell。"
                        + "工作目录不是沙箱，请只执行用户任务所需的命令。",
                parameters);
    }

    @Override
    public String execute(String arguments) throws IOException, InterruptedException {
        String command;
        try {
            JSONObject args = JSON.parseObject(arguments);
            if (args == null || args.size() != 1 || !(args.get("command") instanceof String value)
                    || StrUtil.isBlank(value)) {
                return "工具错误：参数必须是只包含非空字符串 command 的 JSON 对象。";
            }
            command = value;
        } catch (RuntimeException e) {
            return "工具错误：arguments 不是有效的 JSON 对象。";
        }
        Files.createDirectories(workDirectory);
        Process process = new ProcessBuilder(commandLine(command))
                .directory(workDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        BoundedOutput output = new BoundedOutput(maxOutputChars);
        Thread reader = new Thread(() -> {
            try (Reader stream = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    output.append(buffer, count);
                }
            } catch (IOException e) {
                output.readFailed();
            }
        }, "shell-output");
        reader.setDaemon(true);
        reader.start();
        try {
            // 不提供交互输入，避免命令等待终端输入。
            process.getOutputStream().close();
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                stopProcessTree(process);
            }
            // 子进程可能仍持有输出管道，等待读取也必须有上限。
            reader.join(1000);
            String status = completed ? "退出码：" + process.exitValue()
                    : "工具错误：命令超时（" + timeout.toMillis() + " ms），已请求终止进程树。";
            if (reader.isAlive()) {
                status += "\n输出读取未结束，已停止等待（可能有后台子进程）。";
            }
            return status + "\n" + output.text();
        } finally {
            if (process.isAlive()) {
                stopProcessTree(process);
            }
            process.getInputStream().close();
        }
    }

    private List<String> commandLine(String command) {
        if (!windows) {
            return List.of("/bin/sh", "-c", command);
        }
        String script = """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
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
        // 尽力终止仍可枚举到的子进程；ProcessBuilder 不提供系统沙箱或作业对象。
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) {
            descendants.get(i).destroyForcibly();
        }
        process.destroyForcibly();
    }

    /**
     * 超过上限时保留开头约 2/5 和结尾约 3/5，中间换成一行说明：构建和测试的结论、报错通常在最后，
     * 只保留开头会把它们截掉。
     */
    private static final class BoundedOutput {
        private final int limit;
        private final int headLimit;
        private final int tailLimit;
        private final StringBuilder head = new StringBuilder();
        /** 开头之后的内容只保留最后 tailLimit 个字符；攒到两倍再裁剪，摊销删除的开销。 */
        private final StringBuilder tail = new StringBuilder();
        private long dropped;
        private boolean readFailed;

        private BoundedOutput(int limit) {
            this.limit = limit;
            this.headLimit = limit * 2 / 5;
            this.tailLimit = limit - headLimit;
        }

        private synchronized void append(char[] buffer, int count) {
            int kept = Math.min(count, headLimit - head.length());
            head.append(buffer, 0, kept);
            tail.append(buffer, kept, count - kept);
            if (tail.length() > tailLimit * 2) {
                int excess = tail.length() - tailLimit;
                tail.delete(0, excess);
                dropped += excess;
            }
        }

        private synchronized void readFailed() {
            readFailed = true;
        }

        private synchronized String text() {
            int excess = Math.max(0, tail.length() - tailLimit);
            long omitted = dropped + excess;
            return head + (omitted > 0 ? "\n[输出已截断：上限 " + limit + " 字符，省略了中间 " + omitted
                    + " 个字符；需要完整输出时重定向到文件，再用 grep 或 read_file 查看]\n" : "")
                    + tail.substring(excess) + (readFailed ? "\n[输出读取中断，结果可能不完整]" : "");
        }
    }
}
