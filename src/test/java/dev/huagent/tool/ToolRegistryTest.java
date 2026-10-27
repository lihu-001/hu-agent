package dev.huagent.tool;

import dev.huagent.permission.ToolPermission;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {
    @Test
    void permissionRunsBeforeToolAndUnknownToolsDoNotAsk() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        AgentTool tool = new AgentTool() {
            public ToolDefinition definition() {
                return new ToolDefinition("write", "test", new JSONObject());
            }

            public String execute(String arguments) {
                executions.incrementAndGet();
                return arguments;
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(tool), call -> {
            checks.incrementAndGet();
            return "allowed".equals(call.arguments()) ? null : "test denied";
        });
        assertTrue(registry.execute(new ToolCall("a", "write", "denied")).startsWith("权限拒绝"));
        assertEquals(0, executions.get());
        assertEquals("allowed", registry.execute(new ToolCall("b", "write", "allowed")));
        assertEquals(1, executions.get());
        assertTrue(registry.execute(new ToolCall("c", "unknown", "{}")).contains("未知工具"));
        assertEquals(2, checks.get());
    }

    @Test
    void permissionFailuresDoNotExecuteAndDoNotLeakDetails() throws Exception {
        AgentTool unexpected = new AgentTool() {
            public ToolDefinition definition() {
                return tool("write").definition();
            }

            public String execute(String arguments) {
                fail("权限检查失败后不能执行工具");
                return "";
            }
        };
        for (ToolPermission permission : List.<ToolPermission>of(
                call -> { throw new IOException("private"); },
                call -> { throw new IllegalStateException("private"); })) {
            String result = new ToolRegistry(List.of(unexpected), permission)
                    .execute(new ToolCall("a", "write", "{}"));
            assertTrue(result.startsWith("权限拒绝"));
            assertFalse(result.contains("private"));
        }
        assertThrows(InterruptedException.class, () -> new ToolRegistry(List.of(unexpected), call -> {
            throw new InterruptedException();
        }).execute(new ToolCall("a", "write", "{}")));
    }

    @Test
    void preservesRegistrationOrderAndDispatchesByExactName() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(tool("a"), tool("b")));
        assertEquals(List.of("a", "b"), registry.definitions().stream().map(ToolDefinition::name).toList());
        assertEquals("b:{}", registry.execute(new ToolCall("id", "b", "{}")));
        assertTrue(registry.execute(new ToolCall("id", "B", "{}")).contains("未知工具"));
        assertThrows(UnsupportedOperationException.class, () -> registry.definitions().clear());
    }

    @Test
    void rejectsDuplicateAndBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(List.of(tool("a"), tool("a"))));
        assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(List.of(tool(" "))));
    }

    @Test
    void returnsIoErrorsWithoutLeakingDetailsAndPropagatesInterruptions() throws Exception {
        AgentTool failing = new AgentTool() {
            public ToolDefinition definition() {
                return tool("broken").definition();
            }

            public String execute(String arguments) throws IOException, InterruptedException {
                if ("interrupt".equals(arguments)) {
                    throw new InterruptedException();
                }
                throw new IOException("private path");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(failing));
        String error = registry.execute(new ToolCall("id", "broken", "{}"));
        assertTrue(error.contains("IOException"));
        assertFalse(error.contains("private"));
        assertThrows(InterruptedException.class,
                () -> registry.execute(new ToolCall("id", "broken", "interrupt")));
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            public ToolDefinition definition() {
                return new ToolDefinition(name, name, new JSONObject());
            }

            public String execute(String arguments) {
                return name + ":" + arguments;
            }
        };
    }
}
