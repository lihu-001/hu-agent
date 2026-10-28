package dev.huagent.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 用户中断当前任务（hu 里任务运行中按 Esc 或 Ctrl+C，与 Claude Code 相同）。
 * ChatLoop 读到一行输入后 begin、回到提示符前 end，期间主线程就是"任务线程"；
 * 行编辑器在任务期间监听按键，按下时调用 request：置位中断请求并中断任务线程，
 * 正在进行的模型请求、Shell 命令、Hook 与 MCP 调用随之抛出 InterruptedException，由 ChatLoop 结束本轮。
 *
 * <p>任务线程正在等确认回答时不从外部中断它：行编辑器会以空回答（拒绝）结束这次确认，
 * 线程中断推迟到回答读完之后（见 answers），避免中断丢下回答、留下一行没人取的输入。
 * 没有接上行编辑器时（教程入口、输入被重定向）不会有中断请求，行为与之前相同。
 */
public final class TurnInterrupt {
    private final CopyOnWriteArrayList<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();
    /** 以下字段都在本对象的锁下读写。 */
    private Thread worker;
    private boolean requested;
    private boolean answering;

    /** 任务开始（true）与结束（false）时在任务线程上通知；hu 的行编辑器据此开始、停止监听按键。 */
    public void addListener(Consumer<Boolean> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** 当前线程开始执行一轮任务；上一次未处理的中断请求作废。 */
    public void begin() {
        synchronized (this) {
            worker = Thread.currentThread();
            requested = false;
        }
        notifyListeners(true);
    }

    /** 本轮结束：之后的中断请求不再生效；由中断请求留下的线程中断状态一并清除。 */
    public void end() {
        boolean clear;
        synchronized (this) {
            if (worker == null) {
                return;
            }
            clear = requested && worker == Thread.currentThread();
            worker = null;
            requested = false;
        }
        if (clear) {
            Thread.interrupted();
        }
        notifyListeners(false);
    }

    /** 按键线程调用：没有进行中的任务时什么都不做。 */
    public void request() {
        synchronized (this) {
            if (worker == null) {
                return;
            }
            requested = true;
            if (!answering) {
                worker.interrupt();
            }
        }
    }

    /** 本轮是否已被用户中断；ChatLoop 在检查点据此结束本轮。 */
    public synchronized boolean requested() {
        return requested;
    }

    /**
     * 确认回答的输入：任务线程读回答期间，中断请求只置位、不中断线程；读完后若已请求中断，
     * 在任务线程上补发中断，后续的阻塞调用（如子代理的下一次模型请求）随即结束。其他线程读取时原样转交。
     */
    public BufferedReader answers(BufferedReader answers) {
        Objects.requireNonNull(answers);
        return new BufferedReader(Reader.nullReader()) {
            @Override
            public String readLine() throws IOException {
                boolean task = startAnswer();
                try {
                    return answers.readLine();
                } finally {
                    if (task && finishAnswer()) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
    }

    /** 返回当前线程是不是任务线程。 */
    private synchronized boolean startAnswer() {
        answering = worker == Thread.currentThread();
        return answering;
    }

    /** 返回等待回答期间是否请求了中断。 */
    private synchronized boolean finishAnswer() {
        answering = false;
        return requested;
    }

    private void notifyListeners(boolean running) {
        for (Consumer<Boolean> listener : listeners) {
            listener.accept(running);
        }
    }
}
