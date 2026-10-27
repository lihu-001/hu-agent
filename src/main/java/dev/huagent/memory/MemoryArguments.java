package dev.huagent.memory;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Set;

/** 三个记忆工具共用的参数检查：必须是 JSON 对象，不允许未知字段。 */
final class MemoryArguments {
    private MemoryArguments() {
    }

    static JSONObject parse(String arguments, Set<String> allowed) {
        JSONObject args;
        try {
            args = JSON.parseObject(arguments);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("arguments 必须是有效的 JSON 对象。");
        }
        if (args == null || !allowed.containsAll(args.keySet())) {
            throw new IllegalArgumentException("参数只能包含 " + String.join("、", allowed.stream().sorted().toList()) + "。");
        }
        return args;
    }

    static String requiredString(JSONObject args, String key) {
        if (!(args.get(key) instanceof String value) || StrUtil.isBlank(value)) {
            throw new IllegalArgumentException(key + " 必须是非空字符串。");
        }
        return value.strip();
    }
}
