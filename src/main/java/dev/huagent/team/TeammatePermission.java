package dev.huagent.team;

import dev.huagent.permission.PermissionPolicy;
import dev.huagent.permission.ToolPermission;
import dev.huagent.tool.ToolCall;

import java.util.Objects;

/**
 * 队友的权限检查：与 Lead 同一套规则。允许与硬拒绝在队友线程本地决定；
 * 需要确认时把请求交给主线程的 TeamConsole，阻塞到用户回答——队友线程从不读终端。
 */
public final class TeammatePermission implements ToolPermission {
    private final PermissionPolicy policy;
    private final TeamConsole console;
    private final String member;

    public TeammatePermission(PermissionPolicy policy, TeamConsole console, String member) {
        this.policy = Objects.requireNonNull(policy);
        this.console = Objects.requireNonNull(console);
        this.member = Objects.requireNonNull(member);
    }

    @Override
    public String denialReason(ToolCall call) throws InterruptedException {
        PermissionPolicy.Decision decision = policy.evaluate(call);
        return switch (decision.action()) {
            case ALLOW -> null;
            case DENY -> decision.reason();
            case ASK -> console.confirm(member, decision.reason(), call) ? null : "用户未批准本次调用。";
        };
    }
}
