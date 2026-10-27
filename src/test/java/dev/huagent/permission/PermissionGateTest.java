package dev.huagent.permission;

import dev.huagent.tool.ToolCall;
import dev.huagent.tool.file.WorkspaceFiles;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PermissionGateTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"y", "yes", " Y ", " YES "})
    void explicitApprovalAllowsOnlyCurrentCall(String answer) throws Exception {
        BufferedReader input = new BufferedReader(new StringReader(answer + "\nn\n"));
        StringWriter output = new StringWriter();
        PermissionGate gate = gate(input, output);
        ToolCall call = call("write_file", Map.of("path", "new.txt", "content", "中文"));
        assertNull(gate.denialReason(call));
        assertNotNull(gate.denialReason(call));
        assertTrue(output.toString().contains("write_file [id]"));
        assertTrue(output.toString().contains(call.arguments()));
        assertEquals(2, output.toString().split("权限确认>", -1).length - 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"n", "no", "", " ", "yes all", "allow", "exit", "quit"})
    void otherAnswersDeny(String answer) throws Exception {
        assertNotNull(gate(new BufferedReader(new StringReader(answer + "\n")), new StringWriter())
                .denialReason(call("shell", Map.of("command", "echo test"))));
    }

    @Test
    void eofDeniesAndDoesNotGrantFutureCalls() throws Exception {
        PermissionGate gate = gate(new BufferedReader(new StringReader("")), new StringWriter());
        ToolCall call = call("write_file", Map.of("path", "note.txt", "content", "x"));
        assertTrue(gate.denialReason(call).contains("输入已结束"));
        assertNotNull(gate.denialReason(call));
    }

    @Test
    void allowAndHardDenyNeverConsumeApprovalInput() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader("yes\n"));
        StringWriter output = new StringWriter();
        PermissionGate gate = gate(input, output);
        assertNull(gate.denialReason(call("read_file", Map.of("path", "note.txt"))));
        assertNotNull(gate.denialReason(call("shell", Map.of("command", "shutdown"))));
        assertNotNull(gate.denialReason(call("write_file", Map.of("path", "../outside", "content", "x"))));
        assertEquals("", output.toString());
        assertEquals("yes", input.readLine());
    }

    @Test
    void inputFailurePropagatesToRegistryWithoutImplicitApproval() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() throws IOException {
                throw new IOException("terminal closed");
            }
        };
        assertThrows(IOException.class, () -> gate(input, new StringWriter())
                .denialReason(call("shell", Map.of("command", "echo test"))));
    }

    private PermissionGate gate(BufferedReader input, StringWriter output) throws IOException {
        return new PermissionGate(new PermissionPolicy(new WorkspaceFiles(root)), input, new PrintWriter(output));
    }

    private ToolCall call(String name, Map<String, Object> args) {
        return new ToolCall("id", name, JSON.toJSONString(args));
    }
}
