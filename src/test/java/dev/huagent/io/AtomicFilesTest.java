package dev.huagent.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFilesTest {
    @TempDir
    Path directory;

    @Test
    void replacesExistingTargetAndConsumesTemporary() throws IOException {
        Path target = Files.writeString(directory.resolve("record.json"), "old");
        Path temporary = Files.writeString(directory.resolve("record.tmp"), "new");
        AtomicFiles.replace(temporary, target);
        assertEquals("new", Files.readString(target));
        assertFalse(Files.exists(temporary));
    }

    @Test
    void createsMissingTarget() throws IOException {
        Path temporary = Files.writeString(directory.resolve("record.tmp"), "first");
        AtomicFiles.replace(temporary, directory.resolve("record.json"));
        assertEquals("first", Files.readString(directory.resolve("record.json")));
    }

    @Test
    void persistentFailureIsReportedAndKeepsBothFiles() throws IOException {
        // 非空目录不能被文件替换：Windows 报 AccessDenied（重试用尽后抛出），其他系统报 DirectoryNotEmpty。
        Path target = Files.createDirectory(directory.resolve("busy"));
        Files.writeString(target.resolve("inside.txt"), "keep");
        Path temporary = Files.writeString(directory.resolve("record.tmp"), "new");
        assertThrows(IOException.class, () -> AtomicFiles.replace(temporary, target));
        assertEquals("keep", Files.readString(target.resolve("inside.txt")));
        assertEquals("new", Files.readString(temporary));
    }
}
