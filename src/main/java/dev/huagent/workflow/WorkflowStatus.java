package dev.huagent.workflow;

/**
 * 工作流的六个状态：
 * pending --run--> running --全部完成--> completed；某步失败为 failed，中断或进程退出为 paused；
 * cancelled 是终态（workflow_cancel），不能再运行。paused 与 failed 都可以从断点继续。
 */
public enum WorkflowStatus {
    PENDING("pending", "[ ]"),
    RUNNING("running", "[>]"),
    PAUSED("paused", "[=]"),
    COMPLETED("completed", "[x]"),
    FAILED("failed", "[!]"),
    CANCELLED("cancelled", "[-]");

    private final String wireName;
    private final String marker;

    WorkflowStatus(String wireName, String marker) {
        this.wireName = wireName;
        this.marker = marker;
    }

    public String wireName() {
        return wireName;
    }

    public String marker() {
        return marker;
    }

    public static WorkflowStatus parse(String value) {
        for (WorkflowStatus status : values()) {
            if (status.wireName.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("status 只能是 pending、running、paused、completed、failed 或 cancelled");
    }
}
