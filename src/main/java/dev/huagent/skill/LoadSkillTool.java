package dev.huagent.skill;

import dev.huagent.tool.AgentTool;
import dev.huagent.tool.ToolDefinition;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/** 按名称读取技能正文，作为工具结果交给模型；只读技能目录，不接触工作目录或进程。 */
public final class LoadSkillTool implements AgentTool {
    public static final String NAME = "load_skill";

    private final SkillCatalog catalog;

    public LoadSkillTool(SkillCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    @Override
    public ToolDefinition definition() {
        String names = catalog.skills().isEmpty() ? "（当前没有可用技能）"
                : String.join("、", catalog.skills().stream().map(Skill::name).toList());
        return new ToolDefinition(NAME,
                "按名称加载一个技能的完整说明。系统提示词只列出技能名称和用途，"
                        + "当任务与某个技能匹配时先调用本工具，再按返回的说明执行。可用技能：" + names,
                JSON.parseObject("""
                        {"type":"object","properties":{
                        "name":{"type":"string","description":"技能名称，取自系统提示词中的技能目录"}},
                        "required":["name"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws IOException {
        JSONObject args;
        try {
            args = JSON.parseObject(arguments);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("arguments 必须是有效的 JSON 对象。");
        }
        if (args == null || !Set.of("name").containsAll(args.keySet())
                || !(args.get("name") instanceof String value) || StrUtil.isBlank(value)) {
            throw new IllegalArgumentException("参数必须是只包含非空字符串 name 的 JSON 对象。");
        }
        String name = value.strip();
        Skill skill = catalog.find(name).orElseThrow(() -> new IllegalArgumentException(
                "未知技能 " + name + "，可用技能："
                        + (catalog.skills().isEmpty() ? "无" : String.join("、",
                        catalog.skills().stream().map(Skill::name).toList()))));
        return render(catalog, skill);
    }

    /** 每次重读正文，超过上限截断并注明；S18 的 /<技能名> 命令用同一格式交给模型。 */
    public static String render(SkillCatalog catalog, Skill skill) throws IOException {
        String body = catalog.body(skill);
        String suffix = "";
        if (body.length() > SkillCatalog.MAX_BODY_CHARS) {
            body = body.substring(0, SkillCatalog.MAX_BODY_CHARS);
            suffix = "\n…（技能正文已截断，上限 " + SkillCatalog.MAX_BODY_CHARS + " 字符）";
        }
        return "<skill name=\"" + skill.name() + "\">\n" + body + suffix + "\n</skill>";
    }
}
