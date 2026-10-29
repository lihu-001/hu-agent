package dev.huagent.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 只读的 git 查询（项目上下文、grep 的候选文件）：参数列表启动，不经过 Shell，不读 stderr。
 * git 不可用、目录不是仓库、超时或退出码非 0 时返回 null，由调用方退回不依赖 git 的做法。
 */
public final class Git {
    public static final Duration TIMEOUT = Duration.ofSeconds(5);

    private Git() {
    }

    public static String output(Path directory, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.quotepath=off"));
        command.addAll(List.of(args));
        Process process;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            return null;
        }
        try {
            process.getOutputStream().close();
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                return null;
            }
            byte[] bytes = output.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return process.exitValue() == 0 ? new String(bytes, StandardCharsets.UTF_8) : null;
        } catch (IOException | ExecutionException | TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static byte[] readAll(InputStream stream) {
        try (stream) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
