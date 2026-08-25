package com.ai.gateway.domain.model;

import java.util.Locale;
import java.util.Objects;

/**
 * 远端 Agent（A2A peer）的工作负载身份。
 *
 * <p>它<b>不是</b> {@link Principal}：A2A 调用方是工作负载而非用户，因此本记录只承担两件事——
 * 审计维度与信任分级依据。用户身份始终另行获取（{@code ON_BEHALF_OF} 透传原始用户 Token 走
 * {@code AuthenticationPort}，或 {@code SERVICE_ACCOUNT} 映射到预配置的服务账号 Principal）。
 * 从 A2A 消息体读取 {@code orgId} / {@code userId} 是被禁止的第三种做法。</p>
 *
 * <p>{@code peerDigest} 只允许是 SHA-256 十六进制摘要。这条约束由类型本身强制：明文 Token 或
 * 证书内容绝不可能是 64 位小写十六进制串，因此本记录在结构上就无法承载凭据明文，
 * 也就不会因为某处日志打印了身份对象而泄露凭据。</p>
 *
 * @param peerAgentName 来自对端 AgentCard 的 {@code name}，<b>不可信</b>，仅用作审计标签
 * @param peerDigest    已认证凭据（Bearer Token 或客户端证书）的 SHA-256 十六进制摘要
 * @param trustTier     网关侧注册表判定出的信任分级，不接受对端自声明
 * @see TrustTier
 * @since 0.1.0
 */
public record AgentIdentity(
        String peerAgentName,
        String peerDigest,
        TrustTier trustTier
) {

    /** 对端自报名称的长度上限：它只是审计标签，超长部分没有取证价值。 */
    private static final int MAX_PEER_AGENT_NAME = 120;

    /** 对端未提供可用名称时的占位标签。 */
    private static final String UNKNOWN_PEER = "unknown-peer";

    /**
     * 紧凑构造器：归一化审计标签、强制摘要形态。
     *
     * @param peerAgentName 对端自报名称，允许为空
     * @param peerDigest    凭据摘要，必须是 SHA-256 十六进制串
     * @param trustTier     信任分级，不能为 {@code null}
     */
    public AgentIdentity {
        Objects.requireNonNull(trustTier, "trustTier must not be null");
        peerAgentName = normalizeName(peerAgentName);
        peerDigest = normalizeDigest(peerDigest);
    }

    /**
     * 构造一个未认证 peer 的身份。
     *
     * <p>用于公开卡等无凭据场景：分级恒为 {@link TrustTier#UNTRUSTED}，任何能力都不会被投影。</p>
     *
     * @param peerDigest 凭据摘要（未认证场景下通常是连接特征的摘要）
     * @return 未认证身份
     */
    public static AgentIdentity untrusted(String peerDigest) {
        return new AgentIdentity(UNKNOWN_PEER, peerDigest, TrustTier.UNTRUSTED);
    }

    /**
     * 判定该风险等级的能力是否可以进入本 peer 的可见面。
     *
     * @param risk 能力风险等级
     * @return 允许投影时返回 {@code true}
     */
    public boolean allowsProjection(RiskLevel risk) {
        return trustTier.allowsProjection(risk);
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN_PEER;
        }
        // 对端名称会进入审计详情，控制字符必须先剔除，避免污染日志与审计载荷。
        String normalized = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return UNKNOWN_PEER;
        }
        return normalized.length() <= MAX_PEER_AGENT_NAME
                ? normalized
                : normalized.substring(0, MAX_PEER_AGENT_NAME);
    }

    private static String normalizeDigest(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("peerDigest must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "peerDigest must be a lowercase SHA-256 hex digest");
        }
        return normalized;
    }
}
