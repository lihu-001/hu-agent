package dev.huagent.compact;

import dev.huagent.model.ChatMessage;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/** 总结前把完整历史写成 JSONL，每行一条消息；只创建新文件，不覆盖已有记录。 */
public final class TranscriptStore {
    public static final String DIRECTORY_NAME = ".transcripts";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path directory;

    public TranscriptStore(Path directory) {
        this.directory = Objects.requireNonNull(directory);
    }

    public Path directory() {
        return directory;
    }

    public Path save(List<ChatMessage> messages) throws IOException {
        Files.createDirectories(directory);
        String base = "transcript-" + TIME.format(LocalDateTime.now());
        for (int attempt = 0; ; attempt++) {
            Path file = directory.resolve(base + (attempt == 0 ? "" : "-" + attempt) + ".jsonl");
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                for (ChatMessage message : messages) {
                    writer.write(JSON.toJSONString(toJson(message)));
                    writer.newLine();
                }
                return file;
            } catch (FileAlreadyExistsException e) {
                // 同一毫秒内多次保存，换一个序号。
            }
        }
    }

    private static JSONObject toJson(ChatMessage message) {
        JSONObject json = new JSONObject();
        json.put("role", message.role());
        json.put("content", message.content());
        if (message.toolCallId() != null) {
            json.put("tool_call_id", message.toolCallId());
        }
        if (!message.toolCalls().isEmpty()) {
            json.put("tool_calls", message.toolCalls().stream().map(call -> {
                JSONObject item = new JSONObject();
                item.put("id", call.id());
                item.put("name", call.name());
                item.put("arguments", call.arguments());
                return item;
            }).toList());
        }
        return json;
    }
}
