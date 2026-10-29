package dev.huagent.model;

import cn.hutool.core.util.StrUtil;

/** 随用户消息发送的一张图片：mediaType 为 image/png、image/jpeg、image/gif 或 image/webp，data 为 Base64 编码的文件内容。 */
public record ChatImage(String mediaType, String data) {
    public ChatImage {
        if (StrUtil.isBlank(mediaType) || StrUtil.isBlank(data)) {
            throw new IllegalArgumentException("图片的类型与内容不能为空");
        }
    }

    /** Chat Completions 的 image_url 使用的 data URL。 */
    public String dataUrl() {
        return "data:" + mediaType + ";base64," + data;
    }
}
