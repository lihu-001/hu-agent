package dev.huagent.context;

import dev.huagent.io.Git;
import dev.huagent.io.GitFixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProjectContextTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    @TempDir
    Path root;

    @Test
    void outsideGitHasIdentityEnvironmentAndInstructionsButNoGitStatus() throws Exception {
        assumeTrue(Git.output(root, "rev-parse") == null, "临时目录位于 git 仓库内");
        Path work = Files.createDirectories(root.resolve("work"));
        Path home = Files.createDirectories(root.resolve("home"));
        Files.writeString(home.resolve("AGENTS.md"), "用户级：回答用中文");
        Files.writeString(work.resolve("CLAUDE.md"), "项目级：没有 AGENTS.md 时读 CLAUDE.md");
        String text = ProjectContext.render(work, home, TODAY);
        assertTrue(text.startsWith(ProjectContext.IDENTITY), text);
        assertTrue(text.contains("<env>\n工作目录：" + work.toRealPath() + "\ngit 仓库：否\n"), text);
        assertTrue(text.contains("今天：2026-10-28"), text);
        assertFalse(text.contains("<git-status>"), text);
        int user = text.indexOf("用户级：回答用中文");
        int project = text.indexOf("项目级：没有 AGENTS.md 时读 CLAUDE.md");
        assertTrue(text.indexOf(ProjectContext.INSTRUCTIONS_PREFACE) < user && user < project, "用户级在前：" + text);
        assertTrue(text.contains("<instructions path=\"" + work.toRealPath().resolve("CLAUDE.md") + "\">"), text);
        assertTrue(text.endsWith("</instructions>\n\n" + ProjectContext.CRITICAL), "要点重述在最末尾：" + text);
    }

    @Test
    void insideGitShowsBranchStatusCommitsAndInstructionsFromRepositoryRootDown() throws Exception {
        assumeTrue(GitFixture.available(), "本机没有 git");
        Path repo = Files.createDirectories(root.resolve("repo"));
        GitFixture.init(repo);
        Files.writeString(repo.resolve("AGENTS.md"), "根目录说明");
        Files.writeString(repo.resolve("CLAUDE.md"), "有 AGENTS.md 时不读");
        GitFixture.run(repo, "add", ".");
        GitFixture.run(repo, "commit", "-m", "第一次提交");
        GitFixture.run(repo, "checkout", "-b", "feat/status");
        Path module = Files.createDirectories(repo.resolve("module"));
        Files.writeString(module.resolve("AGENTS.md"), "模块说明");
        String text = ProjectContext.render(module, null, TODAY);
        assertTrue(text.contains("git 仓库：是（仓库根目录 " + repo.toRealPath() + "）"), text);
        assertTrue(text.contains("当前分支：feat/status"), text);
        assertTrue(text.contains("?? module/"), "工作区状态：" + text);
        Files.writeString(repo.resolve("AGENTS.md"), "根目录说明（已修改）");
        assertTrue(ProjectContext.render(repo, null, TODAY).contains("工作区状态：\n M AGENTS.md\n"),
                "第一行的行首空格（未暂存）保留");
        assertTrue(text.contains("第一次提交"), text);
        assertTrue(text.indexOf("根目录说明") < text.indexOf("模块说明"), "外层在前：" + text);
        assertFalse(text.contains("有 AGENTS.md 时不读"), text);
    }

    @Test
    void longInstructionsAreTruncated() throws Exception {
        Path work = Files.createDirectories(root.resolve("long"));
        Files.writeString(work.resolve("AGENTS.md"), "说".repeat(ProjectContext.MAX_INSTRUCTIONS_CHARS + 10));
        String text = ProjectContext.render(work, null, TODAY);
        assertTrue(text.contains("说".repeat(ProjectContext.MAX_INSTRUCTIONS_CHARS) + "\n…（说明超过 "), text);
        assertFalse(text.contains("说".repeat(ProjectContext.MAX_INSTRUCTIONS_CHARS + 1)));
    }
}
