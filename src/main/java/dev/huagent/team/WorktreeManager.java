package dev.huagent.team;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 每个队友一个 git worktree：工作目录 .worktrees/&lt;name&gt;，分支 wt/&lt;name&gt;，从创建时的 HEAD 检出。
 * 只在工作目录本身是 git 仓库根时可用；不满足条件时报错，不悄悄退回共用目录。
 * git 以参数列表启动、不经过 Shell，有超时；worktree 与分支在会话结束后保留，合并由 Lead 或用户完成。
 * worktree 只隔离文件和分支，不是安全沙箱。
 */
public final class WorktreeManager {
    public static final String DIRECTORY_NAME = ".worktrees";
    public static final String BRANCH_PREFIX = "wt/";
    static final long TIMEOUT_SECONDS = 30;
    static final int MAX_OUTPUT_CHARS = 4000;

    /** 一个队友的工作副本；base 是创建时主目录所在的分支，队友同步与合并都以它为准。 */
    public record Worktree(String name, Path path, String branch, String base) {
    }

    private final Path root;

    public WorktreeManager(Path workDirectory) {
        this.root = Objects.requireNonNull(workDirectory).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /**
     * 为 name 准备 worktree：已经存在且登记为 wt/&lt;name&gt; 时直接复用（队友只活在一次会话中，重名再创建时接着用上次的副本），
     * 否则新建。任何前置条件不满足都抛 IllegalArgumentException，git 中途失败抛 IOException 并保留现场供手工处理。
     */
    public synchronized Worktree prepare(String name) throws IOException, InterruptedException {
        MessageBus.validateName(name);
        String top = requireRepositoryRoot();
        String branch = BRANCH_PREFIX + name;
        Path path = root.resolve(DIRECTORY_NAME).resolve(name);
        String base = git(root, "rev-parse", "--abbrev-ref", "HEAD").strip();
        if ("HEAD".equals(base)) {
            throw new IllegalArgumentException("工作目录处于分离 HEAD 状态，请先切换到一个分支再创建 worktree");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (registeredBranch(path).equals("refs/heads/" + branch)) {
                return new Worktree(name, path, branch, base);
            }
            throw new IllegalArgumentException(DIRECTORY_NAME + "/" + name + " 已存在但不是分支 " + branch
                    + " 的 worktree，请手工检查或换一个队友名");
        }
        if (run(root, "show-ref", "--verify", "--quiet", "refs/heads/" + branch).exitCode() == 0) {
            throw new IllegalArgumentException("分支 " + branch + " 已存在但没有对应的 worktree，请手工处理或换一个队友名");
        }
        exclude(top);
        Files.createDirectories(path.getParent());
        Result added = run(root, "worktree", "add", "-b", branch, path.toString(), "HEAD");
        if (added.exitCode() != 0) {
            throw new IOException("git worktree add 失败，未删除可能残留的目录或分支：" + added.output().strip());
        }
        return new Worktree(name, path, branch, base);
    }

    /** 工作目录是否为 git 仓库根（不是仓库、或只是仓库里的子目录都返回 false）。 */
    public boolean isRepositoryRoot() throws InterruptedException {
        try {
            requireRepositoryRoot();
            return true;
        } catch (IllegalArgumentException | IOException e) {
            return false;
        }
    }

    private String requireRepositoryRoot() throws IOException, InterruptedException {
        Result top = run(root, "rev-parse", "--show-toplevel");
        if (top.exitCode() != 0) {
            throw new IllegalArgumentException("工作目录 " + root + " 不是 git 仓库，不能创建 worktree；"
                    + "请把 agent.work-dir 指向一个 git 仓库的根目录，或不使用 worktree");
        }
        Path toplevel = Path.of(top.output().strip());
        if (!Files.isSameFile(toplevel, root)) {
            throw new IllegalArgumentException("工作目录 " + root + " 只是仓库 " + toplevel
                    + " 中的子目录，worktree 会检出整个仓库；请把 agent.work-dir 指向仓库根目录，或不使用 worktree");
        }
        return toplevel.toString();
    }

    /** path 在 git worktree list 中登记的分支；未登记返回空串。 */
    private String registeredBranch(Path path) throws IOException, InterruptedException {
        String list = git(root, "worktree", "list", "--porcelain");
        String current = null;
        for (String line : list.split("\\R")) {
            if (line.startsWith("worktree ")) {
                Path listed = Path.of(line.substring("worktree ".length()).strip());
                current = Files.exists(listed) && Files.isSameFile(listed, path) ? "" : null;
            } else if (current != null && line.startsWith("branch ")) {
                return line.substring("branch ".length()).strip();
            }
        }
        return "";
    }

    /** 把 .worktrees/ 写进本仓库的 .git/info/exclude，只影响本机，不改用户的 .gitignore。 */
    private void exclude(String top) throws IOException, InterruptedException {
        Path exclude = Path.of(git(root, "rev-parse", "--git-path", "info/exclude").strip());
        if (!exclude.isAbsolute()) {
            exclude = Path.of(top).resolve(exclude);
        }
        String entry = "/" + DIRECTORY_NAME + "/";
        String text = Files.exists(exclude) ? Files.readString(exclude, StandardCharsets.UTF_8) : "";
        if (text.lines().noneMatch(line -> line.strip().equals(entry))) {
            Files.createDirectories(exclude.getParent());
            Files.writeString(exclude, (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + entry + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static String git(Path directory, String... args) throws IOException, InterruptedException {
        Result result = run(directory, args);
        if (result.exitCode() != 0) {
            throw new IOException("git " + String.join(" ", args) + " 失败：" + result.output().strip());
        }
        return result.output();
    }

    record Result(int exitCode, String output) {
    }

    /** 参数列表启动 git，不经过 Shell；超时则终止进程。 */
    static Result run(Path directory, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IllegalArgumentException("无法启动 git，请确认已安装并在 PATH 中");
        }
        process.getOutputStream().close();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git " + String.join(" ", args) + " 超时（" + TIMEOUT_SECONDS + " 秒）");
            }
            return new Result(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("git 输出读取失败", e);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String read(InputStream stream) {
        try (stream) {
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return text.length() <= MAX_OUTPUT_CHARS ? text : text.substring(0, MAX_OUTPUT_CHARS) + "…（已截断）";
        } catch (IOException e) {
            return "";
        }
    }
}
