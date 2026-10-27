package dev.huagent.tool.file;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class GlobTool implements AgentTool {
    private static final int MAX_MATCHES = 200;
    private final WorkspaceFiles files;

    public GlobTool(WorkspaceFiles files) {
        this.files = files;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("glob",
                "按相对路径模式查找文件或目录。使用 / 分隔，*、? 匹配单层，独立的 ** 匹配零层或多层目录，"
                        + "例如 **/*.java。排序后最多返回 200 条；不递归进入目录链接。",
                JSON.parseObject("""
                        {"type":"object","properties":{"pattern":{"type":"string"}},
                        "required":["pattern"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws IOException {
        JSONObject args = ToolArguments.parse(arguments, Set.of("pattern"));
        String pattern = ToolArguments.string(args, "pattern", false);
        List<PathMatcher> matchers = compile(pattern);
        TreeSet<String> matches = new TreeSet<>();
        boolean[] omitted = {false};
        // 除符号链接外，也不进入 Windows junction；只保存排序靠前的结果。
        Files.walkFileTree(files.root(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // S13：根目录下的 .worktrees 是队友的完整副本，不计入查找结果。
                if (dir.getParent() != null && dir.getParent().equals(files.root())
                        && ".worktrees".equals(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                addMatch(dir);
                return dir.toRealPath().equals(dir) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                addMatch(file);
                return FileVisitResult.CONTINUE;
            }

            private void addMatch(Path path) {
                if (path.equals(files.root()) || !matches(files.root().relativize(path), matchers)) {
                    return;
                }
                try {
                    files.resolve(path.toString());
                } catch (IllegalArgumentException | IOException e) {
                    return;
                }
                matches.add(files.root().relativize(path).toString().replace('\\', '/'));
                if (matches.size() > MAX_MATCHES) {
                    matches.pollLast();
                    omitted[0] = true;
                }
            }
        });
        return matches.isEmpty() ? "[无匹配项]" : String.join("\n", matches)
                + (omitted[0] ? "\n[匹配项已截断，上限 200 条，请缩小 pattern 范围]" : "");
    }

    private List<PathMatcher> compile(String pattern) {
        if (pattern.startsWith("/") || pattern.contains("\\") || pattern.contains(":")) {
            throw new IllegalArgumentException("pattern 必须使用工作目录内的相对路径和 / 分隔符。");
        }
        List<PathMatcher> matchers = new ArrayList<>();
        for (String part : pattern.split("/", -1)) {
            if (part.isEmpty() || part.equals("..") || part.equals(".")) {
                throw new IllegalArgumentException("pattern 不能包含空路径段、. 或 ..。");
            }
            try {
                matchers.add(part.equals("**") ? null : FileSystems.getDefault().getPathMatcher("glob:" + part));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("pattern 不是有效的 glob 模式。");
            }
        }
        return matchers;
    }

    private boolean matches(Path path, List<PathMatcher> matchers) {
        // 按路径段匹配，使 **/ 同时支持零层目录（JDK 的整路径 glob 不提供此语义）。
        boolean[] previous = new boolean[path.getNameCount() + 1];
        previous[0] = true;
        for (PathMatcher matcher : matchers) {
            boolean[] current = new boolean[previous.length];
            current[0] = matcher == null && previous[0];
            for (int i = 1; i < current.length; i++) {
                current[i] = matcher == null ? previous[i] || current[i - 1]
                        : previous[i - 1] && matcher.matches(path.getName(i - 1));
            }
            previous = current;
        }
        return previous[path.getNameCount()];
    }
}
