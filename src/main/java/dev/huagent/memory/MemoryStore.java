package dev.huagent.memory;

import cn.hutool.core.util.StrUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 跨会话记忆：工作目录 .memory/ 下每条记忆一个 &lt;name&gt;.md 文件，开头 frontmatter 记录 name、type、description，
 * 其后是正文。启动时只把元数据渲染成索引放进系统提示词；正文由 memory_read 按需读取，每次重读文件。
 * 文件就是唯一的数据源，没有额外的索引文件，手动编辑或删除文件后重启即可生效。
 */
public final class MemoryStore {
    public static final String DIRECTORY_NAME = ".memory";
    public static final String EXTENSION = ".md";
    public static final int MAX_MEMORIES = 100;
    public static final int MAX_NAME_CHARS = 64;
    public static final int MAX_DESCRIPTION_CHARS = 200;
    public static final int MAX_CONTENT_CHARS = 4000;
    static final int MAX_FILE_BYTES = 64 * 1024;
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final String DELIMITER = "---";

    private final Path directory;

    public MemoryStore(Path directory) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    /** 扫描全部记忆，按名称排序；单个文件无效只告警跳过，目录不存在视为没有记忆，目录不可读才抛出。 */
    public List<MemoryEntry> list(Consumer<String> warnings) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(directory)) {
            throw new IOException("记忆路径不是目录：" + directory);
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path file : stream) {
                files.add(file);
            }
        }
        files.sort(null);
        List<MemoryEntry> entries = new ArrayList<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (entries.size() == MAX_MEMORIES) {
                warnings.accept("记忆> 已达到 " + MAX_MEMORIES + " 条上限，忽略 " + fileName);
                continue;
            }
            try {
                entries.add(load(file, nameOf(fileName)).entry());
            } catch (IllegalArgumentException | IOException e) {
                warnings.accept("记忆> 跳过 " + fileName + "：" + message(e));
            }
        }
        return entries;
    }

    /** 每次重新读取文件，文件被手动修改后立即生效。 */
    public String read(String name) throws IOException {
        validateName(name);
        Path file = fileOf(name);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("没有名为 " + name + " 的记忆");
        }
        Loaded loaded = load(file, name);
        MemoryEntry entry = loaded.entry();
        return "<memory name=\"" + entry.name() + "\" type=\"" + entry.type().value() + "\">\n"
                + "描述：" + entry.description() + "\n\n" + loaded.body() + "\n</memory>";
    }

    /** 创建或整体覆盖一条记忆；先写临时文件再替换，避免写到一半留下损坏的记忆。返回是否为新建。 */
    public boolean write(String name, MemoryType type, String description, String content) throws IOException {
        validateName(name);
        Objects.requireNonNull(type);
        if (StrUtil.isBlank(description) || description.strip().length() > MAX_DESCRIPTION_CHARS
                || description.strip().contains("\n") || description.strip().contains("\r")) {
            throw new IllegalArgumentException("description 必须是非空单行文字，最多 " + MAX_DESCRIPTION_CHARS + " 字符");
        }
        if (StrUtil.isBlank(content) || content.strip().length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("content 必须非空，最多 " + MAX_CONTENT_CHARS + " 字符");
        }
        Files.createDirectories(directory);
        Path file = fileOf(name);
        boolean created = !Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        if (!created && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("记忆 " + name + " 不是普通文件，拒绝覆盖");
        }
        if (created && list(warning -> { }).size() >= MAX_MEMORIES) {
            throw new IllegalArgumentException("记忆已达到 " + MAX_MEMORIES + " 条上限，请先合并或删除旧记忆");
        }
        String text = DELIMITER + "\nname: " + name + "\ntype: " + type.value()
                + "\ndescription: " + description.strip() + "\n" + DELIMITER + "\n\n" + content.strip() + "\n";
        Path temp = Files.createTempFile(directory, name + "-", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return created;
    }

    public void delete(String name) throws IOException {
        validateName(name);
        Path file = fileOf(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("没有名为 " + name + " 的记忆");
        }
        Files.delete(file);
    }

    /** 放进系统提示词的索引：只有名称、类型和描述；没有记忆时返回空字符串。 */
    public static String renderIndex(List<MemoryEntry> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("已保存的记忆（来自之前的会话，只列出名称和描述；需要时用 memory_read 读取正文）：");
        for (MemoryEntry entry : entries) {
            text.append("\n- ").append(entry.name()).append(" [").append(entry.type().value()).append("]：")
                    .append(entry.description());
        }
        return text.toString();
    }

    public static void validateName(String name) {
        if (StrUtil.isBlank(name) || name.length() > MAX_NAME_CHARS || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name 必须是小写字母、数字和连字符，最多 " + MAX_NAME_CHARS + " 字符");
        }
    }

    private record Loaded(MemoryEntry entry, String body) {
    }

    private Path fileOf(String name) {
        return directory.resolve(name + EXTENSION);
    }

    private static String nameOf(String fileName) {
        return fileName.substring(0, fileName.length() - EXTENSION.length());
    }

    private Loaded load(Path file, String expectedName) throws IOException {
        // 不跟随链接：记忆只能是 .memory 目录中的普通文件。
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("不是普通文件");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("文件已被删除");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件超过 64 KiB");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("不是有效的 UTF-8");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !DELIMITER.equals(lines.get(0).strip())) {
            throw new IllegalArgumentException("必须以 --- 开头的 frontmatter 开始");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        int end = -1;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (DELIMITER.equals(line.strip())) {
                end = i;
                break;
            }
            if (StrUtil.isBlank(line)) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("frontmatter 第 " + (i + 1) + " 行应为 key: value");
            }
            String key = line.substring(0, colon).strip();
            if (fields.putIfAbsent(key, line.substring(colon + 1).strip()) != null) {
                throw new IllegalArgumentException("frontmatter 字段重复：" + key);
            }
        }
        if (end < 0) {
            throw new IllegalArgumentException("frontmatter 缺少结束的 ---");
        }
        String name = fields.get("name");
        if (!expectedName.equals(name)) {
            throw new IllegalArgumentException("name 必须与文件名一致");
        }
        validateName(name);
        MemoryType type = MemoryType.parse(fields.get("type"));
        String description = fields.get("description");
        if (StrUtil.isBlank(description) || description.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("description 必须非空，最多 " + MAX_DESCRIPTION_CHARS + " 字符");
        }
        String body = String.join("\n", lines.subList(end + 1, lines.size())).strip();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("正文为空");
        }
        return new Loaded(new MemoryEntry(name, type, description, file), body);
    }

    private static String message(Exception e) {
        return e instanceof IllegalArgumentException ? e.getMessage()
                : "读取失败（" + e.getClass().getSimpleName() + "）";
    }
}
