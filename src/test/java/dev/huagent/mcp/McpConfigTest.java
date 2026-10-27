package dev.huagent.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigTest {
    @TempDir
    Path project;

    @Test
    void missingFileMeansNoServers() throws IOException {
        McpConfig.Configuration configuration = McpConfig.read(project);
        assertTrue(configuration.servers().isEmpty());
        assertEquals("", configuration.source());
    }

    @Test
    void parsesFullServerDefinitionWithDefaults() throws IOException {
        write("""
                ﻿{"mcpServers":{
                  "docs":{"command":"node","args":["server.js","--port","0"],"env":{"TOKEN":"${DOCS_TOKEN}"},
                          "timeout":5,"allow":["search"],"trust_files":["lib/a.js"]},
                  "calc":{"command":"calc-server"}}}
                """);
        List<McpConfig.Server> servers = McpConfig.read(project).servers();
        assertEquals(2, servers.size());
        McpConfig.Server docs = servers.get(0);
        assertEquals("docs", docs.name());
        assertEquals("node", docs.command());
        assertEquals(List.of("server.js", "--port", "0"), docs.args());
        assertEquals(Map.of("TOKEN", "${DOCS_TOKEN}"), docs.env());
        assertEquals(5, docs.timeout().toSeconds());
        assertEquals(List.of("search"), docs.allow());
        assertEquals(List.of("lib/a.js"), docs.trustFiles());
        McpConfig.Server calc = servers.get(1);
        assertEquals(List.of(), calc.args());
        assertEquals(McpConfig.DEFAULT_TIMEOUT_SECONDS, calc.timeout().toSeconds());
        assertTrue(calc.allow().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{}",
            "{\"mcpServers\":[]}",
            "{\"mcpServers\":{},\"extra\":1}",
            "{\"mcpServers\":{\"Docs\":{\"command\":\"x\"}}}",
            "{\"mcpServers\":{\"my_docs\":{\"command\":\"x\"}}}",
            "{\"mcpServers\":{\"docs\":{}}}",
            "{\"mcpServers\":{\"docs\":{\"command\":\" \"}}}",
            "{\"mcpServers\":{\"docs\":{\"command\":\"x\",\"type\":\"http\"}}}",
            "{\"mcpServers\":{\"docs\":{\"command\":\"x\",\"url\":\"http://a\"}}}",
            "{\"mcpServers\":{\"docs\":{\"command\":\"x\",\"args\":\"a b\"}}}",
            "{\"mcpServers\":{\"docs\":{\"command\":\"x\",\"args\":[1]}}}",
            "{\"mcpServers\":{\"docs\":{\"command\":\"x\",\"timeout\":0}}}",
            "{\"mcpServers\":{\"docs\":{\"command\":\"x\",\"timeout\":301}}}",
            "{\"mcpServers\":{\"docs\":{\"command\":\"x\",\"timeout\":\"5\"}}}",
            "{\"mcpServers\":{\"docs\":{\"command\":\"x\",\"env\":{\"A\":1}}}}",
            "{\"mcpServers\":{\"docs\":{\"command\":\"x\",\"env\":{\"A=B\":\"1\"}}}}",
            "{\"mcpServers\":{\"docs\":{\"command\":\"x\",\"allow\":[\"\"]}}}",
            "not json"})
    void invalidConfigurationIsRejected(String text) throws IOException {
        write(text);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> McpConfig.read(project));
        assertTrue(error.getMessage().startsWith("MCP 配置 "), error.getMessage());
    }

    @Test
    void errorMessageDoesNotEchoSecrets() throws IOException {
        write("{\"mcpServers\":{\"docs\":{\"command\":\"x\",\"env\":{\"TOKEN\":\"sk-secret\"},\"oops\":1}}}");
        String message = assertThrows(IllegalArgumentException.class, () -> McpConfig.read(project)).getMessage();
        assertFalse(message.contains("sk-secret"), message);
    }

    @Test
    void tooManyServersAreRejected() throws IOException {
        StringBuilder text = new StringBuilder("{\"mcpServers\":{");
        for (int i = 0; i <= McpConfig.MAX_SERVERS; i++) {
            text.append(i == 0 ? "" : ",").append("\"s").append(i).append("\":{\"command\":\"x\"}");
        }
        write(text.append("}}").toString());
        assertThrows(IllegalArgumentException.class, () -> McpConfig.read(project));
    }

    @Test
    void environmentReferencesExpandOrFail() throws IOException {
        assertEquals("Bearer abc", McpClient.expand("Bearer ${TOKEN}", Map.of("TOKEN", "abc")));
        assertEquals("$HOME and ${not valid}", McpClient.expand("$HOME and ${not valid}", Map.of()));
        IOException error = assertThrows(IOException.class, () -> McpClient.expand("${MISSING}", Map.of()));
        assertTrue(error.getMessage().contains("MISSING"));
    }

    private void write(String text) throws IOException {
        Path file = project.resolve(McpConfig.DEFAULT_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
