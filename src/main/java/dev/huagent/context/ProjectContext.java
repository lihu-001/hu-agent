package dev.huagent.context;

import cn.hutool.core.util.StrUtil;
import dev.huagent.io.Git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 会话开始时采集的项目上下文，附加在 Lead、子代理与队友的系统提示词末尾（对应 Claude Code 的环境信息、
 * git 状态与 CLAUDE.md）：身份、工作目录、平台与 Shell、日期、git 分支/状态/最近提交，以及用户级和项目级的 AGENTS.md。
 * 只采集一次，是快照；git 不可用或不是仓库时省略 git 部分。
 */
public final class ProjectContext {
    public static final String INSTRUCTIONS_FILE = "AGENTS.md";
    /** 目录里没有 AGENTS.md 时读它，同一个仓库可以和 Claude Code 共用说明。 */
    public static final String FALLBACK_INSTRUCTIONS_FILE = "CLAUDE.md";
    static final int MAX_INSTRUCTIONS_CHARS = 20000;
    static final int MAX_STATUS_LINES = 30;
    static final int RECENT_COMMITS = 5;

    public static final String IDENTITY = "你是 hu-agent（终端命令 hu），运行在用户终端里的编程 Agent。"
            + "用户提到状态栏（statusline）、输入框、提示符、启动页、斜杠命令、权限确认等界面元素时，"
            + "指的是 hu 自己的终端界面，要改它们就是改 hu-agent 的源码。";
    static final String INSTRUCTIONS_PREFACE = "以下是用户和项目提供的说明，必须遵守；与上面的默认做法冲突时以这些说明为准。";
    /**
     * 放在系统提示词最末尾的要点重述：长提示词中间的内容最容易被忽略，最重要的规则在开头和结尾各出现一次
     * （借鉴 oh-my-pi 的提示词写作规范）。
     */
    public static final String CRITICAL = """
            <critical>
            - 涉及修改就调用工具真正去改，改完运行构建或测试验证。
            - 查找和读取用 grep、glob、read_file；shell 只跑构建、测试、git 等命令。
            - 互不依赖的工具调用放在同一次回复里一起发出。
            - 还有能推进的工作就继续做，做完再交给用户。
            - 结论只来自真实的工具结果。
            </critical>""";

    private ProjectContext() {
    }

    /** userDirectory 是 hu 的用户目录（其中的 AGENTS.md 对所有项目生效）；today 写进环境信息。 */
    public static String render(Path workDirectory, Path userDirectory, LocalDate today) {
        Path work = realPath(workDirectory);
        String top = Git.output(work, "rev-parse", "--show-toplevel");
        Path repository = top == null || top.isBlank() ? null : realPath(Path.of(top.strip()));
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        StringBuilder text = new StringBuilder(IDENTITY);
        text.append("\n\n<env>\n工作目录：").append(work)
                .append("\ngit 仓库：").append(repository == null ? "否"
                        : repository.equals(work) ? "是" : "是（仓库根目录 " + repository + "）")
                .append("\n平台：").append(System.getProperty("os.name", "未知")).append(" ")
                .append(System.getProperty("os.version", ""))
                .append("\nshell 工具的语法：").append(windows ? "Windows PowerShell 5.1" : "POSIX /bin/sh")
                .append("\n今天：").append(today)
                .append("\n</env>");
        if (repository != null) {
            // 在仓库根目录取状态，路径都相对仓库根，与工作目录在不在子目录无关。
            text.append("\n\n").append(gitStatus(repository));
        }
        List<Path> files = instructionFiles(work, repository, userDirectory);
        if (!files.isEmpty()) {
            text.append("\n\n").append(INSTRUCTIONS_PREFACE);
            for (Path file : files) {
                text.append("\n\n<instructions path=\"").append(file).append("\">\n")
                        .append(read(file).strip()).append("\n</instructions>");
            }
        }
        return text.append("\n\n").append(CRITICAL).toString();
    }

    private static String gitStatus(Path repository) {
        String branch = Git.output(repository, "branch", "--show-current");
        String status = Git.output(repository, "status", "--short");
        String log = Git.output(repository, "log", "--oneline", "-" + RECENT_COMMITS);
        return "<git-status>\n会话开始时的快照，之后不会自动更新。"
                + "\n当前分支：" + (StrUtil.isBlank(branch) ? "（不在分支上）" : branch.strip())
                + "\n工作区状态：\n" + (status == null ? "（读取失败）" : status.isBlank() ? "（干净）" : limitLines(status))
                + "\n最近提交：\n" + (StrUtil.isBlank(log) ? "（还没有提交）" : log.strip())
                + "\n</git-status>";
    }

    /** 不去掉行首空格：git status --short 的第一列是暂存区状态，空格也有含义。 */
    private static String limitLines(String text) {
        List<String> lines = text.lines().filter(line -> !line.isBlank()).toList();
        if (lines.size() <= MAX_STATUS_LINES) {
            return String.join("\n", lines);
        }
        return String.join("\n", lines.subList(0, MAX_STATUS_LINES))
                + "\n…（还有 " + (lines.size() - MAX_STATUS_LINES) + " 项）";
    }

    /** 用户级在前；项目级从仓库根目录到工作目录逐层查找，外层在前、离工作目录近的在后。 */
    static List<Path> instructionFiles(Path work, Path repository, Path userDirectory) {
        List<Path> files = new ArrayList<>();
        if (userDirectory != null) {
            addInstructions(files, userDirectory);
        }
        List<Path> directories = new ArrayList<>();
        for (Path directory = work; directory != null; directory = directory.getParent()) {
            directories.addFirst(directory);
            if (repository == null || directory.equals(repository)) {
                break;
            }
        }
        if (repository != null && !directories.getFirst().equals(repository)) {
            // 工作目录不在仓库里（例如 git 报告了别的路径）：只看工作目录本身。
            directories = List.of(work);
        }
        for (Path directory : directories) {
            addInstructions(files, directory);
        }
        return files;
    }

    private static void addInstructions(List<Path> files, Path directory) {
        for (String name : List.of(INSTRUCTIONS_FILE, FALLBACK_INSTRUCTIONS_FILE)) {
            Path file = directory.resolve(name);
            if (Files.isRegularFile(file)) {
                if (!files.contains(file)) {
                    files.add(file);
                }
                return;
            }
        }
    }

    private static String read(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_INSTRUCTIONS_CHARS * 4);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() <= MAX_INSTRUCTIONS_CHARS && input.read() < 0 ? text
                    : text.substring(0, Math.min(text.length(), MAX_INSTRUCTIONS_CHARS))
                    + "\n…（说明超过 " + MAX_INSTRUCTIONS_CHARS + " 字符，后面的内容已省略）";
        } catch (IOException e) {
            return "（读取失败：" + e.getClass().getSimpleName() + "）";
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
