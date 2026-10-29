package dev.huagent.model;

import java.io.IOException;

/** 模型正常结束，却既没有文字也没有工具调用。ChatLoop 提醒模型继续后重试，其他调用方按请求失败处理。 */
public class EmptyReplyException extends IOException {
    public EmptyReplyException(String message) {
        super(message);
    }
}
