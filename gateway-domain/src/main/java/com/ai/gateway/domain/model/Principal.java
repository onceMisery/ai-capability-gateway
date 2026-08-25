package com.ai.gateway.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * 已鉴权的调用方身份，由网关在服务端校验企业认证链之后构造。
 *
 * <p>定义两种鉴权模式：</p>
 * <ol>
 * <li>JWT/OIDC（目标模式）：网关校验签名、签发者、受众、过期时间与必需声明。</li>
 * <li>企业 SSO 服务端校验（过渡模式）：网关通过 SSO 系统的内省端点校验凭据。
 * 不引入任何 SSO SDK JAR。</li>
 * </ol>
 *
 * <p>两种模式产出相同的内部 Principal 结构。{@code orgId} 是用户在会话期间选择的
 * 组织上下文，而非凭据的固有声明。除非令牌已包含由身份系统签名的 org 声明，否则网关
 * 必须在写入 Principal 之前校验该用户在该组织中的成员关系。未经验证的 {@code orgId}
 * 绝不能进入 Principal，也不得用于 PRINCIPAL 参数绑定。</p>
 *
 * <p>请求体、查询参数或自定义请求头中携带的 {@code orgId}、{@code tenantId} 或
 * {@code userId} 绝不覆盖 Principal。</p>
 *
 * @param subject 已鉴权的用户标识（如 "user-123"）
 * @param orgId 本会话中已验证的组织上下文
 * @param roles 用户在该组织内的角色
 * @param permissions 用户的能力权限
 * @param authTime 鉴权完成时间
 * @param authMethod 所使用的鉴权方法（如 "JWT"、"SSO"）
 * @since 0.1.0
 */
public record Principal(
        String subject,
        long orgId,
        List<String> roles,
        List<String> permissions,
        Instant authTime,
        String authMethod
) {
    /**
     * 紧凑构造器，对可变集合执行防御性拷贝。
     *
     * @param subject 已鉴权的用户标识
     * @param orgId 已验证的组织上下文
     * @param roles 用户角色
     * @param permissions 用户权限
     * @param authTime 鉴权时间戳
     * @param authMethod 鉴权方法
     */
    public Principal {
        java.util.Objects.requireNonNull(subject, "subject must not be null");
        java.util.Objects.requireNonNull(roles, "roles must not be null");
        java.util.Objects.requireNonNull(permissions, "permissions must not be null");
        java.util.Objects.requireNonNull(authTime, "authTime must not be null");
        java.util.Objects.requireNonNull(authMethod, "authMethod must not be null");
        roles = List.copyOf(roles);
        permissions = List.copyOf(permissions);
    }
}
