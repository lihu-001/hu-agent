package dev.huagent.team;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 文件收件箱：每个成员一个 .mailboxes/&lt;name&gt;.jsonl，发送就是追加一行，读取后删除（每个收件箱只有一个消费者）。
 * 文件负责保存，唤醒靠内存：同一把锁上的 Condition，发送或 wakeAll 后等待方立即醒来，不轮询文件。
 */
public final class MessageBus {
    public static final String DIRECTORY_NAME = ".mailboxes";
    public static final int MAX_CONTENT_CHARS = 8000;
    /** 只允许小写：Windows 文件名不区分大小写，Alice 与 alice 会写进同一个收件箱。 */
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    private final Path directory;
    private final Consumer<String> delivered;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private long generation;

    /** delivered 在写入成功后以收件人名调用，用于唤醒不在本类等待的消费者（主线程）。 */
    public MessageBus(Path directory, Consumer<String> delivered) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.delivered = Objects.requireNonNull(delivered);
    }

    public Path directory() {
        return directory;
    }

    public static void validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("成员名必须是小写字母开头、只含小写字母、数字、_ 或 - 的 1~32 个字符");
        }
    }

    /** 启动时清空上次会话遗留的收件箱：那些线程已经不存在，旧消息不应送给新队友。返回删除的文件数。 */
    public int clear() throws IOException {
        lock.lock();
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return 0;
            }
            int removed = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jsonl")) {
                for (Path file : stream) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    public void send(TeamMessage message) throws IOException {
        validateName(message.to());
        if (message.content().length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("消息最多 " + MAX_CONTENT_CHARS + " 字符");
        }
        lock.lock();
        try {
            Files.createDirectories(directory);
            Files.writeString(fileOf(message.to()), message.toJson() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            generation++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        delivered.accept(message.to());
    }

    /** 取出并删除 name 的全部消息，按发送顺序返回；无法解析的行跳过。 */
    public List<TeamMessage> drain(String name) throws IOException {
        validateName(name);
        lock.lock();
        try {
            Path file = fileOf(name);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Files.delete(file);
            List<TeamMessage> messages = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    messages.add(TeamMessage.parse(line));
                } catch (IllegalArgumentException e) {
                    // 手工改坏的行不影响其他消息。
                }
            }
            return messages;
        } finally {
            lock.unlock();
        }
    }

    /** 唤醒所有等待者，例如任务图变化后让空闲队友去认领。 */
    public void wakeAll() {
        lock.lock();
        try {
            generation++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 等到 name 有消息、任何发送或 wakeAll 发生、或超时；超时只是兜底（外部进程改动 .tasks 时没有事件）。 */
    public void await(String name, Duration timeout) throws InterruptedException {
        validateName(name);
        lock.lock();
        try {
            long start = generation;
            long nanos = timeout.toNanos();
            while (generation == start && !hasMail(name) && nanos > 0) {
                nanos = changed.awaitNanos(nanos);
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean hasMail(String name) {
        try {
            Path file = fileOf(name);
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private Path fileOf(String name) {
        return directory.resolve(name + ".jsonl");
    }
}
