package com.ai.gateway.adapter.mcp;

import java.time.Instant;
import java.util.Objects;

/**
 * MCP 客户端的静态信任档案。
 *
 * <p>档案以已认证 Bearer Token 的 SHA-256 指纹绑定客户端，不接受普通请求头
 * 自声明的 clientId 作为信任依据。</p>
 */
public record McpClientTrustProfile(
        String clientId,
        String tokenFingerprint,
        TokenAssurance tokenAssurance,
        ConfirmationChannel confirmationChannel,
        boolean enabled,
        Instant expiresAt) {

    public McpClientTrustProfile {
        requireText(clientId, "clientId");
        requireText(tokenFingerprint, "tokenFingerprint");
        Objects.requireNonNull(tokenAssurance, "tokenAssurance must not be null");
        Objects.requireNonNull(confirmationChannel,
                "confirmationChannel must not be null");
        tokenFingerprint = tokenFingerprint.trim().toLowerCase(java.util.Locale.ROOT);
        if (!tokenFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "tokenFingerprint must be a lowercase SHA-256 hex digest");
        }
    }

    public boolean allowsHostConfirmation(Instant now) {
        return enabled
                && tokenAssurance == TokenAssurance.HIGH
                && confirmationChannel == ConfirmationChannel.HOST_UI
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum TokenAssurance {
        LOW, MEDIUM, HIGH
    }

    public enum ConfirmationChannel {
        NONE, HOST_UI
    }
}
