package dev.huagent.tool.file;

import dev.huagent.io.Git;
import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 按内容搜索工作目录内的文件（对应 Claude Code 的 Grep）：只读，不经过 Shell，也不需要逐次确认。
 * 在 git 仓库里只搜跟踪的和未被忽略的文件（与 ripgrep 遵守 .gitignore 相同）；不是仓库、没有 git
 * 或 path 本身被忽略时遍历目录，跳过 .git、node_modules、target 等目录。
 * 跳过二进制文件和超过 1 MiB 的文件；非 UTF-8 字节按替换字符处理，只影响显示。
 */
public final class GrepTool implements AgentTool {
    static final int DEFAULT_HEAD_LIMIT = 250;
    static final int MAX_LINE_CHARS = 500;
    static final int MAX_OUTPUT_CHARS = 30000;
    static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", ".hg", ".svn", ".worktrees", "node_modules",
            "target", ".idea", ".gradle", ".venv", "__pycache__");
    private static final int BINARY_PROBE_BYTES = 8000;
    private static final String FILES = "files_with_matches";
    private static final String CONTENT = "content";
    private static final String COUNT = "count";
    private final WorkspaceFiles files;

    public GrepTool(WorkspaceFiles files) {
        this.files = files;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("grep",
                "按正则表达式（Java 语法）搜索工作目录内文件的内容；只读，不需要确认，在 git 仓库里遵守 .gitignore。"
                        + "output_mode：files_with_matches 只列出文件（默认），content 显示匹配行和行号（可用 context "
                        + "同时显示前后几行），count 显示每个文件的匹配数。path 限定搜索的文件或目录（默认整个工作目录）；"
                        + "glob 过滤文件，如 *.java、src/**/*.ts（不含 / 时只匹配文件名）；ignore_case 忽略大小写；"
                        + "head_limit 限制返回的条数（默认 " + DEFAULT_HEAD_LIMIT + "）。"
                        + "例：{\"pattern\":\"上下文剩余\"} 找出含这段文字的文件；"
                        + "{\"pattern\":\"上下文剩余\",\"glob\":\"*.java\",\"output_mode\":\"content\"} 列出匹配行和行号。",
                JSON.parseObject("""
                        {"type":"object","properties":{"pattern":{"type":"string","description":"正则表达式"},
                         "path":{"type":"string","description":"要搜索的文件或目录，相对工作目录，默认整个工作目录"},
                         "glob":{"type":"string","description":"文件过滤，如 *.java、src/**/*.ts"},
                         "output_mode":{"type":"string","enum":["files_with_matches","content","count"]},
                         "ignore_case":{"type":"boolean"},
                         "context":{"type":"integer","minimum":0,"description":"content 模式下每个匹配前后显示的行数"},
                         "head_limit":{"type":"integer","minimum":1}},
                         "required":["pattern"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws IOException, InterruptedException {
        JSONObject args = ToolArguments.parse(arguments,
                Set.of("pattern", "path", "glob", "output_mode", "ignore_case", "context", "head_limit"));
        String regex = ToolArguments.string(args, "pattern", false);
        String path = ToolArguments.optionalString(args, "path");
        String glob = ToolArguments.optionalString(args, "glob");
        String mode = Objects.requireNonNullElse(ToolArguments.optionalString(args, "output_mode"), FILES);
        if (!Set.of(FILES, CONTENT, COUNT).contains(mode)) {
            throw new IllegalArgumentException("output_mode 只能是 files_with_matches、content 或 count。");
        }
        boolean ignoreCase = ToolArguments.bool(args, "ignore_case", false);
        int context = ToolArguments.nonNegativeInt(args, "context", 0);
        int headLimit = ToolArguments.positiveInt(args, "head_limit", DEFAULT_HEAD_LIMIT);
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex, ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("pattern 不是有效的 Java 正则表达式：" + e.getDescription() + "。");
        }
        List<PathMatcher> filter = glob == null ? null : GlobTool.compile(glob);
        Path base = files.resolve(path == null ? "." : path);
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("path 不存在。");
        }
        Map<String, Path> candidates = Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) ? candidates(base)
                : new TreeMap<>(Map.of(display(base), base));

        List<FileMatches> found = new ArrayList<>();
        int searched = 0;
        for (Path file : candidates.values()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("grep 被中断");
            }
            Path relative = file.equals(base) ? file.getFileName() : base.relativize(file);
            if (filter != null && !GlobTool.matches(glob.contains("/") ? relative : relative.getFileName(), filter)) {
                continue;
            }
            List<String> lines = readLines(file);
            if (lines == null) {
                continue;
            }
            searched++;
            List<Integer> hits = new ArrayList<>();
            Matcher matcher = pattern.matcher("");
            for (int i = 0; i < lines.size(); i++) {
                if (matcher.reset(lines.get(i)).find()) {
                    hits.add(i);
                }
            }
            if (!hits.isEmpty()) {
                found.add(new FileMatches(display(file), lines, hits));
            }
        }
        if (found.isEmpty()) {
            return "[无匹配]（搜索了 " + searched + " 个文件）";
        }
        int total = found.stream().mapToInt(match -> match.hits().size()).sum();
        List<String> body = switch (mode) {
            case FILES -> found.stream().map(FileMatches::path).toList();
            case COUNT -> found.stream().map(match -> match.path() + ":" + match.hits().size()).toList();
            default -> contentLines(found, context);
        };
        String header = FILES.equals(mode) ? "找到 " + found.size() + " 个文件"
                : "找到 " + total + " 处匹配（" + found.size() + " 个文件）";
        return render(header, body, headLimit);
    }

    /** 与 ripgrep 相同：匹配行 path:行号:内容，上下文行 path-行号-内容，不相连的片段之间用 -- 分隔。 */
    private static List<String> contentLines(List<FileMatches> found, int context) {
        List<String> out = new ArrayList<>();
        for (FileMatches match : found) {
            int last = -2;
            Set<Integer> hits = Set.copyOf(match.hits());
            for (int hit : match.hits()) {
                int from = Math.max(0, hit - context);
                int to = Math.min(match.lines().size() - 1, hit + context);
                if (context > 0 && !out.isEmpty() && from > last + 1) {
                    out.add("--");
                }
                for (int i = Math.max(from, last + 1); i <= to; i++) {
                    out.add(match.path() + (hits.contains(i) ? ":" : "-") + (i + 1)
                            + (hits.contains(i) ? ":" : "-") + clip(match.lines().get(i)));
                }
                last = Math.max(last, to);
            }
        }
        return out;
    }

    private static String render(String header, List<String> body, int headLimit) {
        StringBuilder text = new StringBuilder(header);
        int shown = 0;
        for (String line : body) {
            if (shown == headLimit || text.length() + line.length() + 1 > MAX_OUTPUT_CHARS) {
                break;
            }
            text.append('\n').append(line);
            shown++;
        }
        if (shown < body.size()) {
            text.append("\n[只显示了前 ").append(shown).append(" 条，共 ").append(body.size())
                    .append(" 条；可用 path、glob 缩小范围，或调大 head_limit]");
        }
        return text.toString();
    }

    /** git 仓库里用 ls-files 列出跟踪的和未被忽略的文件；拿不到列表时遍历目录。键是显示路径，按字典序排列。 */
    private Map<String, Path> candidates(Path base) throws IOException {
        Map<String, Path> result = new TreeMap<>();
        String listed = Git.output(base, "ls-files", "-z", "--cached", "--others", "--exclude-standard");
        if (listed != null) {
            for (String name : listed.split("\0")) {
                if (name.isEmpty()) {
                    continue;
                }
                Path file = base.resolve(name).normalize();
                // 已删除但仍在索引里的文件、子模块和符号链接都跳过。
                if (file.startsWith(base) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    result.put(display(file), file);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(base) && SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // 与 glob 相同，不进入目录链接和 Windows junction。
                return dir.toRealPath().equals(dir) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    result.put(display(file), file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    /** 文本文件的各行；二进制、过大或读不了的文件返回 null。 */
    private static List<String> readLines(Path file) {
        try {
            if (Files.size(file) > WorkspaceFiles.MAX_FILE_BYTES) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            for (int i = 0; i < Math.min(bytes.length, BINARY_PROBE_BYTES); i++) {
                if (bytes[i] == 0) {
                    return null;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            return null;
        }
    }

    private String display(Path file) {
        return files.root().relativize(file).toString().replace('\\', '/');
    }

    private static String clip(String line) {
        return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + "…";
    }

    private record FileMatches(String path, List<String> lines, List<Integer> hits) {
    }
}
