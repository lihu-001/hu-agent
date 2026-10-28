package dev.huagent.model;

/** 在同一接口、鉴权与连接上切换会话模型，已发出的请求不受影响。 */
public interface ModelSelection {
    String model();

    /**
     * 原子更新后续请求使用的模型，不发送请求；模型是否可用由下一次实际请求确定。
     * 名称可包含 provider/model 形式的路径，不得为空或包含空白、控制字符与零宽空格等不可见的格式字符；
     * 非法输入保留原模型。
     *
     * @throws IllegalArgumentException 名称为 null、空字符串或包含空白、控制或格式字符
     */
    void selectModel(String name);

    /** 校验后原样返回，不修剪名称，以免静默接受非法输入。格式字符从网页复制时常混入，终端里看不出来。 */
    static String checkedModel(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        for (int offset = 0; offset < name.length();) {
            int codePoint = name.codePointAt(offset);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
                throw new IllegalArgumentException("模型名称不能包含空白、控制字符或不可见字符");
            }
            offset += Character.charCount(codePoint);
        }
        return name;
    }
}
