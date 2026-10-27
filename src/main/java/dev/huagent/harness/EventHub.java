package dev.huagent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * S15：程序事件中心。定时计划的完成结果从这里进入对话（后台任务的完成结果仍走自己的队列，
 * 由 ChatLoop 合并送达，避免改坏 S11 的语义）；事件到达时唤醒监听者（控制台），
 * 让空闲的循环发起一轮只有程序事件的请求。
 * 同时记录"哨兵轮"是否在进行中，供 PermissionGate 在程序自动发起的轮次拒绝交互确认。
 */
public final class EventHub {
    private final ConcurrentLinkedDeque<String> items = new ConcurrentLinkedDeque<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean syntheticTurn;

    /** 定时事件到达：入队并唤醒控制台；通知文本由调用方渲染成单条。 */
    public void publish(String notice) {
        items.add(notice);
        fire();
    }

    /** 只唤醒（后台任务完成结果已在其自己的队列里，无需重复入队）。 */
    public void wakeup() {
        fire();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void fire() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // 监听者失败不能影响事件送达。
            }
        }
    }

    /** 取走全部待送达事件；每条只交付一次。 */
    public List<String> drain() {
        List<String> result = new ArrayList<>();
        for (String item; (item = items.poll()) != null; ) {
            result.add(item);
        }
        return result;
    }

    /** 本轮未保存时放回队首，保持原顺序。 */
    public void requeue(List<String> notices) {
        for (int i = notices.size() - 1; i >= 0; i--) {
            items.addFirst(notices.get(i));
        }
    }

    public boolean hasPending() {
        return !items.isEmpty();
    }

    /** ChatLoop 在程序事件哨兵轮期间置位，结束后复位。 */
    public void setSyntheticTurn(boolean active) {
        syntheticTurn = active;
    }

    public boolean inSyntheticTurn() {
        return syntheticTurn;
    }

    public static String render(List<String> notices) {
        return "<system-events>\n定时计划已结束：\n" + String.join("\n\n", notices) + "\n</system-events>";
    }
}
