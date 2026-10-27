package dev.huagent.permission;

import dev.huagent.tool.ToolCall;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.function.BooleanSupplier;

public final class PermissionGate implements ToolPermission {
    private final PermissionPolicy policy;
    private final BufferedReader input;
    private final PrintWriter output;
    /** S15：程序事件发起的哨兵轮次不能交互确认；为 null 时与 S14 相同。 */
    private final BooleanSupplier asyncTurn;

    public PermissionGate(PermissionPolicy policy, BufferedReader input, PrintWriter output) {
        this(policy, input, output, null);
    }

    public PermissionGate(PermissionPolicy policy, BufferedReader input, PrintWriter output,
                          BooleanSupplier asyncTurn) {
        this.policy = policy;
        this.input = input;
        this.output = output;
        this.asyncTurn = asyncTurn;
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
        if (asyncTurn != null && asyncTurn.getAsBoolean()) {
            return "本消息由程序事件自动发起，用户可能不在终端旁，交互确认被拒绝；"
                    + "需要执行时请在用户回来后请求批准。";
        }
        // 与 ChatLoop 共用同一个输入流；确认答案只由这里消费，不进入模型消息历史。
        output.println("权限确认> " + decision.reason());
        output.println("工具> " + call.name() + " [" + call.id() + "]");
        output.println("参数> " + call.arguments());
        output.print("仅允许本次调用？[y/N] ");
        output.flush();
        String answer = input.readLine();
        if (answer == null) {
            return "确认输入已结束，未获得本次授权。";
        }
        return "y".equalsIgnoreCase(answer.strip()) || "yes".equalsIgnoreCase(answer.strip())
                ? null : "用户未批准本次调用。";
    }
}
