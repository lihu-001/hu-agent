package dev.huagent.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 测试用：在临时目录里建 git 仓库；本机没有 git 时由调用方跳过测试。 */
public final class GitFixture {
    private GitFixture() {
    }

    public static boolean available() {
        return Git.output(Path.of("."), "--version") != null;
    }

    /** 新仓库：默认分支 main，提交不签名、不转换换行。 */
    public static void init(Path directory) throws IOException, InterruptedException {
        run(directory, "init", "-b", "main");
        run(directory, "config", "user.email", "test@example.com");
        run(directory, "config", "user.name", "test");
        run(directory, "config", "commit.gpgsign", "false");
        run(directory, "config", "core.autocrlf", "false");
    }

    public static String run(Path directory, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " 失败：" + output);
        }
        return output;
    }
}
