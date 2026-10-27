package dev.huagent.todo;

/** 任务状态；wireName 是模型参数中使用的取值。 */
public enum TodoStatus {
    PENDING("pending", "[ ]"),
    IN_PROGRESS("in_progress", "[>]"),
    COMPLETED("completed", "[x]");

    private final String wireName;
    private final String marker;

    TodoStatus(String wireName, String marker) {
        this.wireName = wireName;
        this.marker = marker;
    }

    public String wireName() {
        return wireName;
    }

    public String marker() {
        return marker;
    }

    public static TodoStatus parse(String value) {
        for (TodoStatus status : values()) {
            if (status.wireName.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("status 只能是 pending、in_progress 或 completed。");
    }
}
