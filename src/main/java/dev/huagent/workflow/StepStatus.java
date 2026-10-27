package dev.huagent.workflow;

/** 工作流单个步骤的状态：pending --执行--> running --成功--> completed；失败为 failed，重跑时回到 pending。 */
public enum StepStatus {
    PENDING("pending", "[ ]"),
    RUNNING("running", "[>]"),
    COMPLETED("completed", "[x]"),
    FAILED("failed", "[!]");

    private final String wireName;
    private final String marker;

    StepStatus(String wireName, String marker) {
        this.wireName = wireName;
        this.marker = marker;
    }

    public String wireName() {
        return wireName;
    }

    public String marker() {
        return marker;
    }

    public static StepStatus parse(String value) {
        for (StepStatus status : values()) {
            if (status.wireName.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("步骤 status 只能是 pending、running、completed 或 failed");
    }
}
