package dev.huagent.workflow;

import dev.huagent.tool.AgentTool;

import java.util.List;
import java.util.Objects;

/**
 * 工作流绑定组装结果：tools 追加到主 Agent（团队模式为 Lead），rule 追加到系统提示词。
 * S18：store 供 /workflows 命令查看，与工具读写同一个目录。
 */
public record WorkflowBinding(List<AgentTool> tools, String rule, WorkflowStore store) {

    public WorkflowBinding {
        tools = List.copyOf(tools);
        Objects.requireNonNull(rule);
        Objects.requireNonNull(store);
    }
}
