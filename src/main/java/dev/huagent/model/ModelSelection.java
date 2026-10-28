package dev.huagent.model;

import java.io.IOException;
import java.util.List;

/** 在同一接口、鉴权与连接上切换会话模型，已发出的请求不受影响。 */
public interface ModelSelection {
    String model();

    /**
     * 原子更新后续请求使用的模型，不发送请求；模型是否可用由下一次实际请求确定。
     * 名称规则见 ModelConfig.checkedModel；非法输入保留原模型。
     *
     * @throws IllegalArgumentException 名称为 null、空字符串或包含空白、控制或格式字符
     */
    void selectModel(String name);

    /**
     * 查询接口提供的模型（GET …/models），按接口返回的顺序，不缓存；名称不合法的条目跳过，它们也无法被选中。
     * 接口不支持或请求失败时抛出 IOException，消息不含响应正文。
     */
    List<String> listModels() throws IOException, InterruptedException;
}
