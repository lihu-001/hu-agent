package dev.huagent.skill;

import cn.hutool.core.util.StrUtil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 技能目录：启动时扫描 skills/&lt;name&gt;/SKILL.md，只把名称和描述放进系统提示词；
 * 正文留在磁盘上，模型调用 load_skill 时才读取，这就是“按需加载”。
 */
public final class SkillCatalog {
    public static final Path DEFAULT_PATH = Path.of(".agents", "skills");
    public static final String SKILL_FILE = "SKILL.md";
    public static final int MAX_SKILLS = 32;
    public static final int MAX_DESCRIPTION_CHARS = 1024;
    public static final int MAX_BODY_CHARS = 12000;
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final int MAX_NAME_CHARS = 64;

    private final Path root;
    private final Map<String, Skill> skills;

    private SkillCatalog(Path root, Map<String, Skill> skills) {
        this.root = root;
        this.skills = Collections.unmodifiableMap(skills);
    }

    public static SkillCatalog empty() {
        return new SkillCatalog(null, new LinkedHashMap<>());
    }

    /** 从项目目录下的 .agents/skills 扫描；目录不存在时为空目录。 */
    public static SkillCatalog scan(Path projectDirectory, Consumer<String> warnings) throws IOException {
        return scanDirectory(projectDirectory.resolve(DEFAULT_PATH), warnings);
    }

    /** 单个技能无效只告警并跳过，不阻止启动；目录整体不可读才抛出。 */
    public static SkillCatalog scanDirectory(Path directory, Consumer<String> warnings) throws IOException {
        Path root;
        try {
            root = directory.toRealPath();
        } catch (NoSuchFileException e) {
            return empty();
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("技能路径不是目录：" + directory);
        }
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    candidates.add(entry);
                }
            }
        }
        // 按目录名排序，系统提示词中的技能顺序与文件系统遍历顺序无关。
        candidates.sort(null);
        Map<String, Skill> skills = new LinkedHashMap<>();
        for (Path entry : candidates) {
            String folder = entry.getFileName().toString();
            if (skills.size() == MAX_SKILLS) {
                warnings.accept("技能> 已达到 " + MAX_SKILLS + " 个上限，忽略 " + folder);
                continue;
            }
            try {
                Skill skill = load(root, entry);
                skills.put(skill.name(), skill);
            } catch (IllegalArgumentException | IOException e) {
                warnings.accept("技能> 跳过 " + folder + "：" + message(e));
            }
        }
        return new SkillCatalog(root, skills);
    }

    private static Skill load(Path root, Path directory) throws IOException {
        Path file = directory.resolve(SKILL_FILE);
        // 技能目录或 SKILL.md 若是指向技能根之外的链接则拒绝。
        if (Files.exists(file) && !file.toRealPath().startsWith(root)) {
            throw new IllegalArgumentException("SKILL.md 链接指向技能目录之外");
        }
        SkillFile.Parsed parsed = SkillFile.read(file);
        String name = parsed.fields().get("name");
        String description = parsed.fields().get("description");
        if (StrUtil.isBlank(name) || name.length() > MAX_NAME_CHARS || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name 必须是小写字母、数字和连字符，最多 " + MAX_NAME_CHARS + " 字符");
        }
        if (!name.equals(directory.getFileName().toString())) {
            throw new IllegalArgumentException("name（" + name + "）必须与目录名一致");
        }
        if (StrUtil.isBlank(description) || description.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("description 必须非空，最多 " + MAX_DESCRIPTION_CHARS + " 字符");
        }
        if (parsed.body().isEmpty()) {
            throw new IllegalArgumentException("SKILL.md 正文为空");
        }
        return new Skill(name, description, file);
    }

    public List<Skill> skills() {
        return List.copyOf(skills.values());
    }

    public Optional<Skill> find(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public Path root() {
        return root;
    }

    /** 每次调用重新读取文件，正文修改无需重启；结构变化后无效则报告错误。 */
    public String body(Skill skill) throws IOException {
        if (Files.exists(skill.file()) && !skill.file().toRealPath().startsWith(root)) {
            throw new IllegalArgumentException("技能 " + skill.name() + " 的 SKILL.md 链接指向技能目录之外");
        }
        SkillFile.Parsed parsed = SkillFile.read(skill.file());
        if (!skill.name().equals(parsed.fields().get("name"))) {
            throw new IllegalArgumentException("技能 " + skill.name() + " 的 name 已被修改，请重启后重新加载目录");
        }
        String body = parsed.body();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("技能 " + skill.name() + " 的正文为空");
        }
        return body;
    }

    /** 放入系统提示词的目录：只有名称和描述，没有正文。 */
    public String renderCatalog() {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("可用技能（只列出名称和用途；需要时调用 load_skill 读取完整说明，再按说明执行）：");
        for (Skill skill : skills.values()) {
            text.append("\n- ").append(skill.name()).append("：").append(skill.description());
        }
        return text.toString();
    }

    private static String message(Exception e) {
        return e instanceof IllegalArgumentException ? e.getMessage()
                : "读取失败（" + e.getClass().getSimpleName() + "）";
    }
}
