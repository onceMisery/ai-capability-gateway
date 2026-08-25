package com.ai.gateway.domain.model;

import java.util.Map;

/**
 * 网关非敏感运行时配置的只读视图。
 *
 * <p>通过管理控制台暴露，用于运维可见性。敏感值（JWT 密钥、API Key、数据库密码）
 * 绝不包含在内。</p>
 *
 * @param environment 部署环境
 * @param authProvider 鉴权提供方（stub 或 sa-token）
 * @param cacheProvider 缓存提供方（stub 或 redis）
 * @param ratelimitProvider 限流提供方（stub 或 sentinel）
 * @param maxRequestSizeBytes 请求体最大字节数
 * @param maxResponseBytes 响应体最大字节数
 * @param defaultTimeoutMs 默认超时（毫秒）
 * @param rateLimits 限流配置
 * @param auditConfig 审计批量写入器配置
 * @param snapshotConfig 快照配置
 * @param sentinelConfig Sentinel 规则阈值（若 Sentinel 启用）
 * @since 0.1.0
 */
public record GatewayConfig(
        String environment,
        String authProvider,
        String cacheProvider,
        String ratelimitProvider,
        int maxRequestSizeBytes,
        long maxResponseBytes,
        int defaultTimeoutMs,
        Map<String, Object> rateLimits,
        Map<String, Object> auditConfig,
        Map<String, Object> snapshotConfig,
        Map<String, Object> sentinelConfig
) {

    /**
     * 紧凑构造器，执行防御性拷贝。
     */
    public GatewayConfig {
        java.util.Objects.requireNonNull(environment, "environment must not be null");
        java.util.Objects.requireNonNull(authProvider, "authProvider must not be null");
        java.util.Objects.requireNonNull(cacheProvider, "cacheProvider must not be null");
        java.util.Objects.requireNonNull(ratelimitProvider, "ratelimitProvider must not be null");
        rateLimits = rateLimits == null ? Map.of() : Map.copyOf(rateLimits);
        auditConfig = auditConfig == null ? Map.of() : Map.copyOf(auditConfig);
        snapshotConfig = snapshotConfig == null ? Map.of() : Map.copyOf(snapshotConfig);
        sentinelConfig = sentinelConfig == null ? Map.of() : Map.copyOf(sentinelConfig);
    }
}
