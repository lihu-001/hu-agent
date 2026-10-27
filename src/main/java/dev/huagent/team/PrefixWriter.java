package dev.huagent.team;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.Objects;

/**
 * 给队友的每一行输出加上 "[name] " 前缀，整行写入共享终端。
 * 多个线程同时输出时只会整行交错，不会在一行中间混入别人的内容。
 */
final class PrefixWriter extends Writer {
    private final PrintWriter target;
    private final String prefix;
    private final StringBuilder line = new StringBuilder();

    PrefixWriter(PrintWriter target, String prefix) {
        this.target = Objects.requireNonNull(target);
        this.prefix = Objects.requireNonNull(prefix);
    }

    static PrintWriter wrap(PrintWriter target, String member) {
        return new PrintWriter(new PrefixWriter(target, "  [" + member + "] "), true);
    }

    @Override
    public synchronized void write(char[] buffer, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            char c = buffer[i];
            if (c == '\n') {
                emit();
            } else if (c != '\r') {
                line.append(c);
            }
        }
    }

    @Override
    public synchronized void flush() {
        // print() 后紧跟 flush() 的半行也整行输出，避免长时间留在缓冲区。
        if (!line.isEmpty()) {
            emit();
        }
    }

    @Override
    public void close() {
        flush();
    }

    private void emit() {
        synchronized (target) {
            target.println(prefix + line);
            target.flush();
        }
        line.setLength(0);
    }
}
