package com.ai.gateway.adapter.a2a;

import com.ai.gateway.domain.model.TrustTier;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A2A 对端（peer）的静态信任档案。
 *
 * <p>档案以<b>已认证凭据的 SHA-256 指纹</b>绑定 peer，绝不以请求头里自声明的 Agent 名称
 * 或对端 AgentCard 的 {@code name} 作为信任依据：那些字段由对端构造，可以任意伪造。
 * 记录里也<b>不存在</b>任何能承载凭据明文的字段——指纹形态由紧凑构造器强制，
 * 明文 Token 不可能是 64 位小写十六进制串。</p>
 *
 * <p>{@link #allowedCapabilityIds()} 只对 {@link A2aIdentityMode#SERVICE_ACCOUNT} 有意义：
 * 服务账号背后没有最终用户，能力范围必须由注册时的显式白名单限定，
 * 而不是由该服务账号在某个租户下「碰巧被授权了什么」来决定。</p>
 *
 * @param peerId              peer 的稳定标识（用于审计与配置定位），不能为空
 * @param tokenFingerprint    已认证凭据的 SHA-256 十六进制摘要，不能为空
 * @param trustTier           网关侧判定的信任分级，不能为 {@code null}
 * @param identityMode        身份来源模式，不能为 {@code null}
 * @param serviceAccountOrgId 服务账号模式下的固定租户号，{@code null} 表示未配置
 * @param allowedCapabilityIds 服务账号模式下的能力白名单，{@code null} 视为空集合
 * @param maxDelegationDepth  该 peer 允许的最大委托深度，必须为正
 * @param enabled             档案是否启用
 * @param expiresAt           档案过期时刻，{@code null} 表示不过期
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public record A2aPeerTrustProfile(
        String peerId,
        String tokenFingerprint,
        TrustTier trustTier,
        A2aIdentityMode identityMode,
        Long serviceAccountOrgId,
        Set<String> allowedCapabilityIds,
        int maxDelegationDepth,
        boolean enabled,
        Instant expiresAt) {

    /**
     * 紧凑构造器：强制指纹形态、冻结白名单、拒绝自相矛盾的组合。
     *
     * @param peerId               peer 标识
     * @param tokenFingerprint     凭据指纹
     * @param trustTier            信任分级
     * @param identityMode         身份来源模式
     * @param serviceAccountOrgId  服务账号租户号
     * @param allowedCapabilityIds 能力白名单
     * @param maxDelegationDepth   最大委托深度
     * @param enabled              是否启用
     * @param expiresAt            过期时刻
     */
    public A2aPeerTrustProfile {
        requireText(peerId, "peerId");
        requireText(tokenFingerprint, "tokenFingerprint");
        Objects.requireNonNull(trustTier, "trustTier must not be null");
        Objects.requireNonNull(identityMode, "identityMode must not be null");
        tokenFingerprint = tokenFingerprint.trim().toLowerCase(Locale.ROOT);
        if (!tokenFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "tokenFingerprint must be a lowercase SHA-256 hex digest");
        }
        if (maxDelegationDepth <= 0) {
            throw new IllegalArgumentException("maxDelegationDepth must be positive");
        }
        allowedCapabilityIds = allowedCapabilityIds == null
                ? Set.of() : Set.copyOf(allowedCapabilityIds);
        if (identityMode == A2aIdentityMode.SERVICE_ACCOUNT) {
            // 服务账号必须同时具备固定租户与显式白名单，缺一不可：
            // 缺租户号就没有隔离依据，缺白名单就等于把整个已授权目录交给一个无人负责的身份。
            if (serviceAccountOrgId == null) {
                throw new IllegalArgumentException(
                        "serviceAccountOrgId is required for SERVICE_ACCOUNT identity mode");
            }
            if (allowedCapabilityIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "allowedCapabilityIds must not be empty for SERVICE_ACCOUNT identity mode");
            }
        }
        if (!identityMode.writeEligible() && trustTier == TrustTier.TRUSTED_CONFIRMATION) {
            // 该身份模式恒为只读，却被配成「具备独立确认通道」，是一个必须在启动期暴露的矛盾配置：
            // 若放过它，卡片会声明出这个 peer 永远无法完成的写路径。
            throw new IllegalArgumentException(
                    "identity mode " + identityMode
                            + " cannot carry trust tier TRUSTED_CONFIRMATION");
        }
    }

    /**
     * 判定档案在给定时刻是否有效。
     *
     * @param now 当前时刻，不能为 {@code null}
     * @return 启用且未过期时返回 {@code true}
     */
    public boolean isActive(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return enabled && (expiresAt == null || expiresAt.isAfter(now));
    }

    /**
     * 返回该档案在给定时刻的<b>有效</b>信任分级。
     *
     * <p>档案失效（禁用或过期）时返回 {@link TrustTier#READ_ONLY} 而不是
     * {@link TrustTier#UNTRUSTED}：凭据本身已经通过认证，只是信任档案不再有效，
     * 因此落到「已认证但未注册」这个默认档位，与从未注册过的 peer 一致。</p>
     *
     * @param now 当前时刻
     * @return 有效信任分级
     */
    public TrustTier effectiveTrustTier(Instant now) {
        return isActive(now) ? trustTier : TrustTier.READ_ONLY;
    }

    /**
     * 判定某个能力是否在服务账号白名单内。
     *
     * <p>非服务账号模式恒返回 {@code true}：那种模式下的能力范围由最终用户的授权结果决定，
     * 白名单不参与判定，也不该在这里叠加一层与真实授权无关的过滤。</p>
     *
     * @param capabilityId 能力标识，允许为 {@code null}
     * @return 允许时返回 {@code true}
     */
    public boolean allowsCapability(String capabilityId) {
        if (identityMode != A2aIdentityMode.SERVICE_ACCOUNT) {
            return true;
        }
        return capabilityId != null && allowedCapabilityIds.contains(capabilityId);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
