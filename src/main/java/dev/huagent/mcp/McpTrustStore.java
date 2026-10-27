package dev.huagent.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import dev.huagent.config.HuHome;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** MCP 授权记录，与 Hook 信任分开保存：批准 Hook 不等于批准启动 MCP server，反之亦然。 */
public final class McpTrustStore {
    private final Path directory;

    /** 显式目录用于隔离测试；正常启动只使用用户目录下的默认存储。 */
    public McpTrustStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public static McpTrustStore userStore() {
        return new McpTrustStore(HuHome.resolve().resolve("mcp-trust"));
    }

    Path recordFile(Path project) {
        return directory.resolve(McpTrustSnapshot.hash(project.toString()) + ".json");
    }

    boolean isTrusted(McpTrustSnapshot snapshot) throws IOException {
        String text;
        try {
            text = Files.readString(recordFile(snapshot.project()));
        } catch (NoSuchFileException e) {
            return false;
        }
        try {
            JSONObject record = JSON.parseObject(text);
            return record != null && Integer.valueOf(1).equals(record.get("version"))
                    && snapshot.project().toString().equals(record.get("project"))
                    && snapshot.fingerprint().equals(record.get("fingerprint"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    void remember(McpTrustSnapshot snapshot) throws IOException {
        JSONObject record = new JSONObject();
        record.put("version", 1);
        record.put("project", snapshot.project().toString());
        record.put("fingerprint", snapshot.fingerprint());
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "mcp-trust-", ".tmp");
        try {
            Files.writeString(temporary, record.toJSONString());
            try {
                Files.move(temporary, recordFile(snapshot.project()),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, recordFile(snapshot.project()), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
