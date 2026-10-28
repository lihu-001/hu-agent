package dev.huagent.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * AgentConsole 与 TeamConsole 读终端的守护线程：System.in 的阻塞读取不能被唤醒，所以读取放在这里；
 * 但只在有人等待输入时才读下一行。行编辑器（LineEditor）在开始读取时画出提示符，
 * 预先读取会让提示符在模型回复期间就出现。
 */
public final class InputPump {
    private final Semaphore demand = new Semaphore(0);
    private final AtomicBoolean requested = new AtomicBoolean();

    /** 读到的行交给 sink；输入结束或终端不可读时交 null，之后线程退出。 */
    public InputPump(BufferedReader terminal, Consumer<String> sink, String threadName) {
        Objects.requireNonNull(terminal);
        Objects.requireNonNull(sink);
        Thread thread = new Thread(() -> {
            String line;
            do {
                try {
                    demand.acquire();
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    line = terminal.readLine();
                } catch (IOException | RuntimeException e) {
                    // 终端不可读按输入结束处理；线程若因异常退出，等待输入的消费者会一直阻塞。
                    line = null;
                }
                // 先清除标记再交出：取到这一行的消费者可以立即请求下一行。
                requested.set(false);
                sink.accept(line);
            } while (line != null);
        }, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    /** 请求读取下一行；上一次请求的行还没读到时什么都不做。 */
    public void request() {
        if (requested.compareAndSet(false, true)) {
            demand.release();
        }
    }
}
