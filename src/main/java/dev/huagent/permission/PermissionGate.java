package dev.huagent.permission;

import dev.huagent.tool.ToolCall;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class PermissionGate implements ToolPermission {
    private final PermissionPolicy policy;
    private final BufferedReader input;
    private final PrintWriter output;
    /** S15：程序事件发起的哨兵轮次不能交互确认；为 null 时与 S14 相同。 */
    private final BooleanSupplier asyncTurn;
    /** 确认提示的样式；hu 默认为 compact，其余构造器为 full，与之前相同。 */
    private final ConfirmPrompt confirm;
    /** hu 的自动审批模式；为 null 或关闭时与之前相同。 */
    private final AutoApprove autoApprove;

    public PermissionGate(PermissionPolicy policy, BufferedReader input, PrintWriter output) {
        this(policy, input, output, null);
    }

    public PermissionGate(PermissionPolicy policy, BufferedReader input, PrintWriter output,
                          BooleanSupplier asyncTurn) {
        this(policy, input, output, asyncTurn, ConfirmPrompt.full());
    }

    public PermissionGate(PermissionPolicy policy, BufferedReader input, PrintWriter output,
                          BooleanSupplier asyncTurn, ConfirmPrompt confirm) {
        this(policy, input, output, asyncTurn, confirm, null);
    }

    public PermissionGate(PermissionPolicy policy, BufferedReader input, PrintWriter output,
                          BooleanSupplier asyncTurn, ConfirmPrompt confirm, AutoApprove autoApprove) {
        this.policy = policy;
        this.input = input;
        this.output = output;
        this.asyncTurn = asyncTurn;
        this.confirm = Objects.requireNonNull(confirm);
        this.autoApprove = autoApprove;
    }

    @Override
    public String denialReason(ToolCall call) throws IOException {
        PermissionPolicy.Decision decision = policy.evaluate(call);
        if (decision.action() == PermissionPolicy.Action.ALLOW) {
            return null;
        }
        if (decision.action() == PermissionPolicy.Action.DENY) {
            return decision.reason();
        }
        // 自动审批：不读终端，程序事件发起的轮次同样放行；硬拒绝已在上面返回。
        if (autoApprove != null && autoApprove.enabled()) {
            synchronized (output) {
                confirm.approved(output, decision.reason(), call, decision.preview(), null);
                output.flush();
            }
            return null;
        }
        if (asyncTurn != null && asyncTurn.getAsBoolean()) {
            return "本消息由程序事件自动发起，用户可能不在终端旁，交互确认被拒绝；"
                    + "需要执行时请在用户回来后请求批准。";
        }
        // 与 ChatLoop 共用同一个输入流；确认答案只由这里消费，不进入模型消息历史。
        confirm.ask(output, decision.reason(), call, decision.preview(), "仅允许本次调用？");
        output.flush();
        String answer = input.readLine();
        if (answer == null) {
            return "确认输入已结束，未获得本次授权。";
        }
        return "y".equalsIgnoreCase(answer.strip()) || "yes".equalsIgnoreCase(answer.strip())
                ? null : "用户未批准本次调用。";
    }
}
