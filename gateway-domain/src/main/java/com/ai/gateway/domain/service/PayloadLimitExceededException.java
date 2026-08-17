package com.ai.gateway.domain.service;

import java.io.Serial;

/**
 * 表示输入或输出 JSON 数据树超过网关预算。
 *
 * <p>使用独立异常类型，让应用层可以把 Provider 输出超限转换成稳定的
 * {@code RESULT_TOO_LARGE} 错误，而不把它误报成普通协议故障。</p>
 */
public final class PayloadLimitExceededException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = -8882799324821854917L;

    /**
     * 创建预算超限异常。
     *
     * @param message 不包含具体 Payload 值的安全错误消息
     */
    public PayloadLimitExceededException(String message) {
        super(message);
    }
}
