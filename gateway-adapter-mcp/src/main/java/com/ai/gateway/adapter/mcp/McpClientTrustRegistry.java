package com.ai.gateway.adapter.mcp;

import com.ai.gateway.domain.model.RequestContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MCP 客户端信任档案查询器。
 *
 * <p>信任事实来自启动配置或可信认证适配器提供的档案。即使请求携带
 * {@code Mcp-Client-Id}，也必须先命中 Bearer Token 指纹档案，随后才校验
 * 自声明 clientId 是否一致。</p>
 */
public final class McpClientTrustRegistry {

    private final Map<String, McpClientTrustProfile> profilesByToken;
    private final Clock clock;

    public McpClientTrustRegistry(Collection<McpClientTrustProfile> profiles) {
        this(profiles, Clock.systemUTC());
    }

    McpClientTrustRegistry(Collection<McpClientTrustProfile> profiles, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Collection<McpClientTrustProfile> safeProfiles =
                profiles == null ? java.util.List.of() : profiles;
        this.profilesByToken = safeProfiles.stream().collect(Collectors.toUnmodifiableMap(
                McpClientTrustProfile::tokenFingerprint,
                Function.identity(),
                (left, right) -> {
                    throw new IllegalArgumentException(
                            "duplicate MCP token fingerprint: " + left.tokenFingerprint());
                }));
    }

    public static McpClientTrustRegistry disabled() {
        return new McpClientTrustRegistry(java.util.List.of());
    }

    /**
     * 只有档案、Bearer 指纹、可选 clientId 绑定和有效期全部通过时才返回 true。
     */
    public boolean isTrusted(RequestContext context) {
        if (context == null) {
            return false;
        }
        String token = bearerToken(context);
        if (token == null) {
            return false;
        }
        Optional<McpClientTrustProfile> profile =
                Optional.ofNullable(profilesByToken.get(sha256(token)));
        if (profile.isEmpty() || !profile.get().allowsHostConfirmation(Instant.now(clock))) {
            return false;
        }
        String declaredClientId = context.header("Mcp-Client-Id");
        return declaredClientId == null || declaredClientId.isBlank()
                || profile.get().clientId().equals(declaredClientId.trim());
    }

    public Optional<McpClientTrustProfile> profile(RequestContext context) {
        String token = context == null ? null : bearerToken(context);
        return token == null
                ? Optional.empty()
                : Optional.ofNullable(profilesByToken.get(sha256(token)));
    }

    private static String bearerToken(RequestContext context) {
        String authorization = context.header("Authorization");
        if (authorization == null
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

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
