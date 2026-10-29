package dev.huagent.prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/** 每次读取工作目录所在仓库的 HEAD，不启动 git 进程，也不缓存分支或仓库位置。 */
public final class GitBranch implements Supplier<String> {
    private final Path workDirectory;

    public GitBranch(Path workDirectory) {
        this.workDirectory = workDirectory.toAbsolutePath().normalize();
    }

    @Override
    public String get() {
        try {
            for (Path directory = workDirectory; directory != null; directory = directory.getParent()) {
                Path git = directory.resolve(".git");
                if (Files.isDirectory(git)) {
                    return branch(git);
                }
                if (Files.exists(git)) {
                    String pointer = read(git).strip();
                    if (!pointer.startsWith("gitdir:")) {
                        return null;
                    }
                    String location = pointer.substring(7).strip();
                    return location.isEmpty() ? null : branch(directory.resolve(location).normalize());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 仓库被删除、切换中或无权读取时隐藏，不影响输入。
        }
        return null;
    }

    private static String branch(Path git) throws IOException {
        String head = read(git.resolve("HEAD")).strip();
        String prefix = "ref: refs/heads/";
        if (head.startsWith(prefix)) {
            String name = head.substring(prefix.length());
            return name.isBlank() || name.codePoints().anyMatch(c -> Character.isWhitespace(c)
                    || Character.isISOControl(c)) ? null : name;
        }
        return head.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
                ? "detached@" + head.substring(0, 7) : null;
    }

    private static String read(Path file) throws IOException {
        // HEAD 与 gitdir 都是小文本；损坏或误指向大文件时不要占用输入线程。
        if (!Files.isRegularFile(file) || Files.size(file) > 8192) {
            throw new IOException("无效的 Git 元数据");
        }
        return Files.readString(file);
    }
}
