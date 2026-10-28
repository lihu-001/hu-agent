package dev.huagent.prompt;

/** 把来自配置或模型的文字写到终端前的转义，Hook、MCP 授权与权限确认共用。 */
public final class TerminalText {
    private TerminalText() {
    }

    /**
     * 控制字符与格式字符（如 U+202E 双向覆盖）转成 \\uXXXX，其余原样：
     * 不会被终端当作控制序列执行，也不能让显示的内容与实际执行的不一致。
     */
    public static String visible(String text) {
        StringBuilder result = new StringBuilder();
        text.codePoints().forEach(code -> {
            if (Character.isISOControl(code) || Character.getType(code) == Character.FORMAT) {
                result.append(String.format("\\u%04x", code));
            } else {
                result.appendCodePoint(code);
            }
        });
        return result.toString();
    }
}
