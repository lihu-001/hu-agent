package dev.huagent.memory;

import java.util.Arrays;

/** 记忆分类：决定什么值得跨会话保存，也帮助模型判断何时读取。 */
public enum MemoryType {
    /** 用户是谁：角色、熟悉的技术、沟通偏好。 */
    USER("user"),
    /** 用户对做法的纠正或认可，附原因。 */
    FEEDBACK("feedback"),
    /** 无法从代码和提交记录得知的项目背景、目标与约束。 */
    PROJECT("project"),
    /** 外部资源的位置：文档、看板、工单链接。 */
    REFERENCE("reference");

    private final String value;

    MemoryType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MemoryType parse(String value) {
        for (MemoryType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("type 只能是 " + String.join("、",
                Arrays.stream(values()).map(MemoryType::value).toList()));
    }
}
