package dev.huagent.model;

import java.io.IOException;

/** 请求内容超出模型上下文窗口，缩短或总结后重试可能成功。 */
public class ContextTooLargeException extends IOException {
    public ContextTooLargeException(String message) {
        super(message);
    }
}
