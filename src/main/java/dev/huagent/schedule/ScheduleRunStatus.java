package dev.huagent.schedule;

/** 定时计划最近一次运行的状态；running 只会在运行期间出现在文件里。 */
public enum ScheduleRunStatus {
    RUNNING("running", "[~]"),
    COMPLETED("completed", "[x]"),
    FAILED("failed", "[!]"),
    TIMED_OUT("timed_out", "[!]"),
    CANCELLED("cancelled", "[-]");

    private final String wireName;
    private final String marker;

    ScheduleRunStatus(String wireName, String marker) {
        this.wireName = wireName;
        this.marker = marker;
    }

    public String wireName() {
        return wireName;
    }

    public String marker() {
        return marker;
    }

    public static ScheduleRunStatus parse(String value) {
        for (ScheduleRunStatus status : values()) {
            if (status.wireName.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("lastStatus 只能是 running、completed、failed、timed_out 或 cancelled");
    }
}
