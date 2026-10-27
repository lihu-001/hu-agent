package dev.huagent.hook;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import dev.huagent.config.HuHome;
import dev.huagent.io.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** 每个项目独立保存，项目配置没有可写入授权状态的字段。 */
public final class HookTrustStore {
    private final Path directory;

    /** 显式目录用于隔离测试；正常启动只使用用户目录下的默认存储。 */
    public HookTrustStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public static HookTrustStore userStore() {
        return new HookTrustStore(HuHome.resolve().resolve("hook-trust"));
    }

    Path recordFile(Path project) {
        return directory.resolve(HookTrustSnapshot.hash(project.toString()) + ".json");
    }

    boolean isTrusted(HookTrustSnapshot snapshot) throws IOException {
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

    void remember(HookTrustSnapshot snapshot) throws IOException {
        JSONObject record = new JSONObject();
        record.put("version", 1);
        record.put("project", snapshot.project().toString());
        record.put("fingerprint", snapshot.fingerprint());
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "hook-trust-", ".tmp");
        try {
            Files.writeString(temporary, record.toJSONString());
            AtomicFiles.replace(temporary, recordFile(snapshot.project()));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
