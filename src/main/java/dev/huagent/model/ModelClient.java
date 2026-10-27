package dev.huagent.model;

import dev.huagent.tool.ToolDefinition;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
public interface ModelClient {
    ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools)
            throws IOException, InterruptedException;
}
