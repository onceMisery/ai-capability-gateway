package com.ai.gateway.domain.model;

import java.time.Instant;

/**
 * 写操作两阶段协议中 Prepare 阶段签发的短时效、一次性确认令牌。
 *
 * <p>规定 Prepare 完成后，网关返回的确认令牌必须满足：</p>
 *
 * <ul>
 * <li><strong>一次性</strong> - 被 Confirm 消费后不可复用。重复的 Confirm 调用返回同一
 * 操作的当前状态，不会创建新操作。</li>
 * <li><strong>短时效</strong> - 带过期时间；若 {@code expiresAt} 前未调用 Confirm，
 * 操作迁移至 {@link OperationState#EXPIRED}。</li>
 * <li><strong>绑定</strong> - 绑定到 {@code operationId}、Principal 摘要、组织与参数摘要，
 * 以防替换攻击。</li>
 * <li><strong>签名</strong> - 携带服务端签名，Confirm 阶段在继续前先验证。</li>
 * </ul>
 *
 * <p>Confirm 阶段网关必须校验：</p>
 * <ol>
 * <li>令牌签名。</li>
 * <li>令牌未过期。</li>
 * <li>令牌未被使用。</li>
 * <li>当前 Principal 与 Prepare 阶段一致。</li>
 * <li>操作仍处于 PREPARED 状态。</li>
 * <li>能力未被下线，清单摘要未被吊销。</li>
 * </ol>
 *
 * @param token 不透明令牌字符串
 * @param operationId 关联的操作 ID
 * @param principalDigest Prepare 阶段 Principal 的摘要
 * @param orgId 组织上下文
 * @param argumentsDigest 所绑定参数的摘要
 * @param serverSignature 对所有绑定字段的服务端签名
 * @param expiresAt 令牌过期时间
 * @param used 令牌是否已被消费
 * @since 0.1.0
 */
public record ConfirmationToken(
        String token,
        String operationId,
        String principalDigest,
        long orgId,
        String argumentsDigest,
        String serverSignature,
        Instant expiresAt,
        boolean used
) {

    /**
     * 紧凑构造器，对必填字段执行 null 检查。
     *
     * @param token 令牌字符串
     * @param operationId 操作 ID
     * @param principalDigest Principal 摘要
     * @param orgId 组织 ID
     * @param argumentsDigest 参数摘要
     * @param serverSignature 服务端签名
     * @param expiresAt 过期时间
     * @param used 已使用标志
     */
    public ConfirmationToken {
        java.util.Objects.requireNonNull(token, "token must not be null");
        java.util.Objects.requireNonNull(operationId, "operationId must not be null");
        java.util.Objects.requireNonNull(principalDigest, "principalDigest must not be null");
        java.util.Objects.requireNonNull(argumentsDigest, "argumentsDigest must not be null");
        java.util.Objects.requireNonNull(serverSignature, "serverSignature must not be null");
        java.util.Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
