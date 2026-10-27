package dev.huagent.task;

/** 持久化任务的三个状态：pending --claim--> in_progress --complete--> completed。 */
public enum TaskStatus {
    PENDING("pending", "[ ]"),
    IN_PROGRESS("in_progress", "[>]"),
    COMPLETED("completed", "[x]");

    private final String wireName;
    private final String marker;

    TaskStatus(String wireName, String marker) {
        this.wireName = wireName;
        this.marker = marker;
    }

    public String wireName() {
        return wireName;
    }

    public String marker() {
        return marker;
    }

    public static TaskStatus parse(String value) {
        for (TaskStatus status : values()) {
            if (status.wireName.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("status 只能是 pending、in_progress 或 completed");
    }
}
