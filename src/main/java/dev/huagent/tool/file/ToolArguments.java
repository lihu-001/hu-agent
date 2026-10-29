package dev.huagent.tool.file;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Set;

final class ToolArguments {
    private ToolArguments() {
    }

    static JSONObject parse(String arguments, Set<String> allowed) {
        JSONObject args;
        try {
            args = JSON.parseObject(arguments);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("arguments 必须是有效的 JSON 对象。");
        }
        if (args == null || !allowed.containsAll(args.keySet())) {
            throw new IllegalArgumentException("参数必须是 JSON 对象，且不能包含未定义的字段。");
        }
        return args;
    }

    static String string(JSONObject args, String name, boolean allowEmpty) {
        if (!(args.get(name) instanceof String value) || (!allowEmpty && StrUtil.isBlank(value))) {
            throw new IllegalArgumentException(name + " 必须是" + (allowEmpty ? "" : "非空") + "字符串。");
        }
        return value;
    }

    /** 可选字符串：没有这个字段时返回 null；有就必须是非空字符串。 */
    static String optionalString(JSONObject args, String name) {
        return args.containsKey(name) ? string(args, name, false) : null;
    }

    static boolean bool(JSONObject args, String name, boolean defaultValue) {
        if (!args.containsKey(name)) {
            return defaultValue;
        }
        if (!(args.get(name) instanceof Boolean value)) {
            throw new IllegalArgumentException(name + " 必须是 true 或 false。");
        }
        return value;
    }

    static int nonNegativeInt(JSONObject args, String name, int defaultValue) {
        if (!args.containsKey(name)) {
            return defaultValue;
        }
        if (!(args.get(name) instanceof Integer value) || value < 0) {
            throw new IllegalArgumentException(name + " 必须是非负整数。");
        }
        return value;
    }

    static int positiveInt(JSONObject args, String name, int defaultValue) {
        if (!args.containsKey(name)) {
            return defaultValue;
        }
        if (!(args.get(name) instanceof Integer value) || value <= 0) {
            throw new IllegalArgumentException(name + " 必须是正整数。");
        }
        return value;
    }
}
