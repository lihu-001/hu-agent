package dev.huagent.hook;

import com.alibaba.fastjson2.JSONObject;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolCall;
import dev.huagent.tool.ToolDefinition;
import dev.huagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HookRegistryTest {
    private final ToolCall call = new ToolCall("call-1", "test", "{}");

    @Test
    void runsHooksInRegistrationOrderAroundPermissionAndExecution() throws Exception {
        List<String> events = new ArrayList<>();
        AgentHook first = recordingHook("first", events);
        AgentHook second = recordingHook("second", events);
        List<AgentHook> hooks = new ArrayList<>(List.of(first, second));
        ToolRegistry registry = new ToolRegistry(List.of(tool(() -> {
            events.add("execute");
            return "完成";
        })), request -> {
            events.add("permission");
            return null;
        }, hooks, message -> fail(message));
        hooks.clear(); // 构造时固定注册顺序，外部修改列表不影响执行。

        assertEquals("完成", registry.execute(call));
        assertEquals(List.of("first.before", "second.before", "permission", "execute",
                "first.after:完成", "second.after:完成"), events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"blocked", ""})
    void anyNonNullPreResultBlocksRemainingHooksPermissionAndTool(String reason) throws Exception {
        AgentHook deny = new AgentHook() {
            public String preToolUse(ToolCall request) {
                return reason;
            }

            public void postToolUse(ToolCall request, String result, long elapsedNanos) {
                fail("拒绝后不能触发 PostToolUse");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(tool(() -> fail("不能执行工具"))),
                request -> fail("不能询问权限"), List.of(deny, unexpectedHook()), message -> fail(message));
        assertTrue(registry.execute(call).startsWith("钩子拒绝"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime"})
    void preFailureBlocksExecutionWithoutLeakingExceptionDetails(String type) throws Exception {
        AgentHook broken = new AgentHook() {
            public String preToolUse(ToolCall request) throws IOException {
                throwFailure(type);
                return null;
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(tool(() -> fail("不能执行工具"))),
                request -> fail("不能询问权限"), List.of(broken, unexpectedHook()), message -> fail(message));
        String result = registry.execute(call);
        assertTrue(result.contains("前置检查失败"));
        assertFalse(result.contains("private"));
    }

    @Test
    void preApprovalCannotOverridePermissionDenial() throws Exception {
        List<String> events = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry(List.of(tool(() -> fail("权限拒绝不能执行"))),
                request -> "denied", List.of(recordingHook("hook", events)), message -> fail(message));
        assertTrue(registry.execute(call).startsWith("权限拒绝"));
        assertEquals(List.of("hook.before"), events);
    }

    @Test
    void unknownToolDoesNotTriggerHooksOrPermission() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(), request -> fail("不应检查未知工具"),
                List.of(unexpectedHook()), message -> fail(message));
        assertTrue(registry.execute(call).contains("未知工具"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime"})
    void postFailurePreservesResultAndContinuesOtherHooks(String type) throws Exception {
        List<String> events = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        AgentHook broken = new AgentHook() {
            public void postToolUse(ToolCall request, String result, long elapsedNanos) throws IOException {
                throwFailure(type);
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(tool(() -> "已写入")), request -> null,
                List.of(broken, recordingHook("next", events)), warnings::add);
        assertEquals("已写入", registry.execute(call));
        assertEquals(List.of("next.before", "next.after:已写入"), events);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("[call-1]"));
        assertFalse(warnings.get(0).contains("private"));
    }

    @Test
    void warningReceiverFailureCannotLoseResult() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(tool(() -> "已写入")), request -> null,
                List.of(new AgentHook() {
                    public void postToolUse(ToolCall request, String result, long elapsedNanos) throws IOException {
                        throw new IOException();
                    }
                }), message -> {
            throw new IllegalStateException();
        });
        assertEquals("已写入", registry.execute(call));
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime", "argument"})
    void toolErrorsReachPostHooksAndCaller(String type) throws Exception {
        List<String> events = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry(List.of(tool(() -> {
            if ("argument".equals(type)) {
                throw new IllegalArgumentException("bad argument");
            }
            throwFailure(type);
            return "";
        })), request -> null, List.of(recordingHook("hook", events)), message -> fail(message));
        String result = registry.execute(call);
        assertTrue(result.startsWith("工具错误"));
        assertFalse(result.contains("private"));
        assertEquals(List.of("hook.before", "hook.after:" + result), events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pre", "permission", "tool"})
    void interruptionPropagatesWithoutRunningPostHooks(String stage) {
        AgentHook hook = new AgentHook() {
            public String preToolUse(ToolCall request) throws InterruptedException {
                if ("pre".equals(stage)) {
                    throw new InterruptedException();
                }
                return null;
            }

            public void postToolUse(ToolCall request, String result, long elapsedNanos) {
                fail("中断不应伪造完成事件");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(tool(() -> {
            assertEquals("tool", stage);
            throw new InterruptedException();
        })), request -> {
            if ("permission".equals(stage)) {
                throw new InterruptedException();
            }
            return null;
        }, List.of(hook), message -> fail(message));
        assertThrows(InterruptedException.class, () -> registry.execute(call));
    }

    private AgentHook recordingHook(String name, List<String> events) {
        return new AgentHook() {
            public String preToolUse(ToolCall request) {
                assertEquals(call, request);
                events.add(name + ".before");
                return null;
            }

            public void postToolUse(ToolCall request, String result, long elapsedNanos) {
                assertEquals(call, request);
                assertTrue(elapsedNanos >= 0);
                events.add(name + ".after:" + result);
            }
        };
    }

    private AgentHook unexpectedHook() {
        return new AgentHook() {
            public String preToolUse(ToolCall request) {
                return fail("不应执行此回调");
            }

            public void postToolUse(ToolCall request, String result, long elapsedNanos) {
                fail("不应执行此回调");
            }
        };
    }

    private void throwFailure(String type) throws IOException {
        if ("io".equals(type)) {
            throw new IOException("private");
        }
        throw new IllegalStateException("private");
    }

    private AgentTool tool(Execution execution) {
        return new AgentTool() {
            public ToolDefinition definition() {
                return new ToolDefinition("test", "test", new JSONObject());
            }

            public String execute(String arguments) throws IOException, InterruptedException {
                return execution.run();
            }
        };
    }

    private interface Execution {
        String run() throws IOException, InterruptedException;
    }
}
