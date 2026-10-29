package dev.huagent.tool.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 文件工具的路径检查；不构成系统沙箱，也不防御并发替换路径或硬链接。
 */
public final class WorkspaceFiles {
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    public static final int MAX_READ_CHARS = 60000;
    private final Path root;

    public WorkspaceFiles(Path workDirectory) throws IOException {
        Files.createDirectories(workDirectory);
        root = workDirectory.toRealPath();
    }

    public Path root() {
        return root;
    }

    public Path resolve(String value) throws IOException {
        Path candidate = root.resolve(value).normalize();
        if (!candidate.startsWith(root) && !aliasesRoot(candidate)) {
            throw new IllegalArgumentException("路径超出工作目录。");
        }
        // 新文件尚不存在时，检查最近的已有祖先，避免经目录链接写到工作区外。
        Path ancestor = existingAncestor(candidate);
        Path realAncestor = ancestor.toRealPath();
        if (!realAncestor.startsWith(root)) {
            throw new IllegalArgumentException("路径链接指向工作目录之外。");
        }
        return realAncestor.resolve(ancestor.relativize(candidate));
    }

    /**
     * root 经过 toRealPath；绝对路径可能经 8.3 短文件名（如 C:\Users\RUNNER~1）或目录链接
     * 指向工作目录，字面前缀与 root 不同。只展开与 root 同盘符或同共享的路径：UNC 路径会让 Windows 连接远程主机。
     */
    private boolean aliasesRoot(Path candidate) {
        if (!root.getRoot().equals(candidate.getRoot())) {
            return false;
        }
        try {
            return existingAncestor(candidate).toRealPath().startsWith(root);
        } catch (IOException e) {
            return false;
        }
    }

    private static Path existingAncestor(Path path) {
        Path ancestor = path;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        return ancestor;
    }

    String read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("路径不是可读取的普通文件。");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件超过 1 MiB，不能使用文本文件工具读取或编辑。");
        }
        // 严格解码：编辑二进制或非 UTF-8 文件时不能静默损坏内容。
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    int write(Path path, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("写入内容超过 1 MiB。");
        }
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return bytes.length;
    }
}
