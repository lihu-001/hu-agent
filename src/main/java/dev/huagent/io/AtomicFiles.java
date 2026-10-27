package dev.huagent.io;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 各存储共用的“临时文件写好后替换目标”，读者只会看到旧内容或新内容。 */
public final class AtomicFiles {
    /** 首次失败后最多再等约 1.3 秒（10ms 起翻倍）。 */
    static final int ATTEMPTS = 8;

    private AtomicFiles() {
    }

    /**
     * 用 temporary 原子替换 target；文件系统不支持原子移动时退回普通替换。
     * Windows 上刚写入的文件常被杀毒软件或索引服务短暂打开，此时替换会被拒绝（AccessDeniedException），稍后重试即可。
     */
    public static void replace(Path temporary, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                if (attempt >= ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(10L << (attempt - 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
