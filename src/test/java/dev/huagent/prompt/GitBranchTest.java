package dev.huagent.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class GitBranchTest {
    @TempDir Path root;

    private Path head(Path git, String value) throws Exception {
        Files.createDirectories(git);
        return Files.writeString(git.resolve("HEAD"), value);
    }

    @Test
    void findsUnbornBranchFromSubdirectoryAndRefreshesHead() throws Exception {
        Path repo = root.resolve("repo");
        Path head = head(repo.resolve(".git"), "ref: refs/heads/feature/中文\n");
        Path child = Files.createDirectories(repo.resolve("src/main"));
        GitBranch branch = new GitBranch(child);
        assertEquals("feature/中文", branch.get());
        Files.writeString(head, "ref: refs/heads/next\n");
        assertEquals("next", branch.get());
        Files.writeString(head, "0123456789abcdef0123456789abcdef01234567\n");
        assertEquals("detached@0123456", branch.get());
        Files.delete(head);
        assertNull(branch.get());
    }

    @Test
    void followsRelativeAndAbsoluteGitdirFilesForWorktrees() throws Exception {
        Path main = root.resolve("main/.git");
        head(main, "ref: refs/heads/main\n");
        Path metadata = main.resolve("worktrees/linked");
        head(metadata, "ref: refs/heads/worktree\n");
        Files.writeString(metadata.resolve("commondir"), "../..\n");
        Path linked = Files.createDirectories(root.resolve("linked"));
        Path pointer = Files.writeString(linked.resolve(".git"), "gitdir: ../main/.git/worktrees/linked\n");
        GitBranch branch = new GitBranch(Files.createDirectories(linked.resolve("sub")));
        assertEquals("worktree", branch.get());
        Files.writeString(pointer, "gitdir: " + metadata.toAbsolutePath() + "\n");
        assertEquals("worktree", branch.get());
    }

    @Test
    void hidesMissingMalformedAndOversizedMetadata() throws Exception {
        GitBranch branch = new GitBranch(root);
        assertNull(branch.get());
        Path pointer = Files.writeString(root.resolve(".git"), "not a gitdir\n");
        assertNull(branch.get());
        Files.writeString(pointer, "gitdir: missing\n");
        assertNull(branch.get());
        Files.writeString(pointer, "gitdir: \n");
        assertNull(branch.get());
        Files.delete(pointer);
        Path head = head(pointer, "ref: refs/heads/\n");
        assertNull(branch.get());
        for (String value : new String[]{"garbage", "ref: refs/tags/tag", "ref: refs/heads/a\nb",
                "ref: refs/heads/" + "x".repeat(9000)}) {
            Files.writeString(head, value);
            assertNull(branch.get());
        }
        Files.writeString(head, "a".repeat(64));
        assertEquals("detached@aaaaaaa", branch.get());
    }

    @Test
    void everyBeginRefreshesAndOldConstructorsDoNotDiscoverGit() throws Exception {
        Path head = head(root.resolve(".git"), "ref: refs/heads/first\n");
        FramedPrompt prompt = new FramedPrompt("> ", '-', () -> 100, "m", 1000,
                () -> false, new GitBranch(root));
        StringWriter buffer = new StringWriter();
        PrintWriter output = new PrintWriter(buffer);
        prompt.begin(output);
        Files.writeString(head, "ref: refs/heads/second\n");
        prompt.begin(output);
        Files.delete(head);
        prompt.begin(output);
        var lines = buffer.toString().lines().filter(line -> line.startsWith("--")).toList();
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("git:first"));
        assertTrue(lines.get(1).contains("git:second"));
        assertFalse(lines.get(2).contains("git:"));
        assertFalse(new FramedPrompt("> ", '-', () -> 100, "m", 1000).rule().contains("git:"));
    }
}
