package com.ai.gateway.adapter.a2a;

import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.TrustTier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A2A 对端信任注册表：把「一个带凭据的入站连接」解析成网关自己判定的 {@link AgentIdentity}。
 *
 * <p>信任分级由本注册表<b>单方面</b>决定，链路上只有一个输入被当作事实：请求携带的
 * 已认证凭据。围绕它有三条不可放松的规则：</p>
 * <ol>
 * <li><b>只存指纹</b>：注册表内不保存任何凭据明文，查表用的也是入站凭据的 SHA-256 摘要。
 * 因此即使注册表整体被打印进日志，也不会泄露任何可重放的凭据。</li>
 * <li><b>未命中即只读</b>：认证通过但没有档案的 peer 恒为 {@link TrustTier#READ_ONLY}，
 * 不是「按需提升」。新接入方在完成注册前只能读，这是默认结果而不是异常分支。</li>
 * <li><b>无凭据即未认证</b>：没有 Bearer 的连接得到 {@link TrustTier#UNTRUSTED}，
 * 对应的扩展卡与公开卡等价。</li>
 * </ol>
 *
 * <p>对端自报的 Agent 名称只作为审计标签流入 {@link AgentIdentity#peerAgentName()}，
 * 永不参与任何判定；若档案存在，则以档案里的 {@code peerId} 覆盖自报名称，
 * 让审计里出现的始终是网关自己认得的标识。</p>
 *
 * <p>本类不可变且线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class A2aPeerTrustRegistry {

    /** 对端自报名称所在的请求头，仅用于审计标签。 */
    static final String PEER_NAME_HEADER = "A2A-Peer-Agent";

    /** 未认证连接的身份摘要占位：对全空凭据取摘要，保证形态合法且不可反查出凭据。 */
    private static final String ANONYMOUS_DIGEST = sha256("a2a-anonymous");

    /** 未命中档案时使用的默认最大委托深度。 */
    private static final int DEFAULT_MAX_DELEGATION_DEPTH = 3;

    private final Map<String, A2aPeerTrustProfile> profilesByFingerprint;
    private final Clock clock;

    /**
     * @param profiles 静态信任档案集合，{@code null} 视为空集合
     */
    public A2aPeerTrustRegistry(Collection<A2aPeerTrustProfile> profiles) {
        this(profiles, Clock.systemUTC());
    }

    A2aPeerTrustRegistry(Collection<A2aPeerTrustProfile> profiles, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Collection<A2aPeerTrustProfile> safeProfiles = profiles == null ? List.of() : profiles;
        this.profilesByFingerprint = safeProfiles.stream().collect(Collectors.toUnmodifiableMap(
                A2aPeerTrustProfile::tokenFingerprint,
                Function.identity(),
                (left, right) -> {
                    // 同一指纹配了两份档案时无法判断哪一份是意图，静默取其一会让分级变得不可预测。
                    throw new IllegalArgumentException(
                            "duplicate A2A peer token fingerprint: " + left.peerId());
                }));
    }

    /**
     * 返回一个不含任何档案的注册表。
     *
     * <p>用于「启用了 A2A 入站但还没有注册任何 peer」的部署：所有已认证 peer 都是只读，
     * 这正是期望的初始状态，因此这不是一种降级配置。</p>
     *
     * @return 空注册表
     */
    public static A2aPeerTrustRegistry disabled() {
        return new A2aPeerTrustRegistry(List.of());
    }

    /**
     * 解析入站请求的工作负载身份。
     *
     * @param context 入站请求上下文，允许为 {@code null}
     * @return 身份；无凭据时为 {@link TrustTier#UNTRUSTED}，认证通过但未注册时为
     * {@link TrustTier#READ_ONLY}
     */
    public AgentIdentity identify(RequestContext context) {
        String token = bearerToken(context);
        if (token == null) {
            return AgentIdentity.untrusted(ANONYMOUS_DIGEST);
        }
        String fingerprint = sha256(token);
        A2aPeerTrustProfile profile = profilesByFingerprint.get(fingerprint);
        if (profile == null) {
            return new AgentIdentity(declaredPeerName(context), fingerprint,
                    TrustTier.READ_ONLY);
        }
        // 档案存在时用注册的 peerId 覆盖自报名称：审计里应当出现网关自己认得的标识。
        return new AgentIdentity(profile.peerId(), fingerprint,
                profile.effectiveTrustTier(Instant.now(clock)));
    }

    /**
     * 查询入站请求命中的信任档案。
     *
     * @param context 入站请求上下文，允许为 {@code null}
     * @return 命中且<b>当前有效</b>的档案；无凭据、未命中或档案已失效时返回
     * {@link Optional#empty()}
     */
    public Optional<A2aPeerTrustProfile> profile(RequestContext context) {
        String token = bearerToken(context);
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(profilesByFingerprint.get(sha256(token)))
                .filter(profile -> profile.isActive(Instant.now(clock)));
    }

    /**
     * 返回该请求适用的最大委托深度。
     *
     * <p>未命中档案时返回默认上限，而不是「不限制」：委托深度的作用是阻断 Agent 之间的
     * 无限转发环，未注册的 peer 恰恰是最需要被这条上限约束的一方。</p>
     *
     * @param context 入站请求上下文，允许为 {@code null}
     * @return 最大委托深度，恒为正数
     */
    public int maxDelegationDepth(RequestContext context) {
        return profile(context)
                .map(A2aPeerTrustProfile::maxDelegationDepth)
                .orElse(DEFAULT_MAX_DELEGATION_DEPTH);
    }

    /**
     * 返回该请求适用的身份来源模式。
     *
     * <p>未命中档案时返回 {@link A2aIdentityMode#ON_BEHALF_OF}：未注册 peer 本就只能只读，
     * 而只读路径仍需要一个真实的最终用户身份来做租户隔离——落到服务账号反而需要一个
     * 并不存在的固定租户。</p>
     *
     * @param context 入站请求上下文，允许为 {@code null}
     * @return 身份来源模式
     */
    public A2aIdentityMode identityMode(RequestContext context) {
        return profile(context)
                .map(A2aPeerTrustProfile::identityMode)
                .orElse(A2aIdentityMode.ON_BEHALF_OF);
    }

    /**
     * 提取 Bearer 凭据。
     *
     * @return 凭据明文；不存在或为空时返回 {@code null}
     */
    private static String bearerToken(RequestContext context) {
        if (context == null) {
            return null;
        }
        String authorization = context.header("Authorization");
        if (authorization == null
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /** 读取对端自报名称；{@link AgentIdentity} 会自行归一化与截断。 */
    private static String declaredPeerName(RequestContext context) {
        return context == null ? null : context.header(PEER_NAME_HEADER);
    }

    /**
     * 计算 SHA-256 十六进制摘要。
     *
     * @param value 输入
     * @return 小写十六进制摘要
     */
    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
