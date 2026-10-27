package dev.huagent.background;

import java.util.Locale;

public enum BackgroundStatus {
    RUNNING, COMPLETED, FAILED, TIMED_OUT, CANCELLED;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
