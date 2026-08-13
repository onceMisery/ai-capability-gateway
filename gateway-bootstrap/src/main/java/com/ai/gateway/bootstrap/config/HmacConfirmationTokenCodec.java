package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.model.ConfirmationToken;
import com.ai.gateway.domain.port.ConfirmationTokenCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * HMAC-SHA256 confirmation token codec. Token claims are signed and treated as opaque by HTTP clients.
 */
@Component
public final class HmacConfirmationTokenCodec implements ConfirmationTokenCodec {

    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;

    public HmacConfirmationTokenCodec(
            @Value("${gateway.operation.confirmation-secret:}") String configuredSecret,
            @Value("${gateway.environment:development}") String environment) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            if ("production".equalsIgnoreCase(environment)) {
                throw new IllegalStateException(
                        "gateway.operation.confirmation-secret is required in production");
            }
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.secret = generated;
        } else {
            byte[] configured = configuredSecret.getBytes(StandardCharsets.UTF_8);
            if (configured.length < 32) {
                throw new IllegalArgumentException("confirmation secret must contain at least 32 bytes");
            }
            this.secret = configured.clone();
        }
    }

    @Override
    public ConfirmationToken issue(String operationId, String principalDigest, long orgId,
                                   String argumentsDigest, Instant expiresAt) {
        String payload = encode(operationId) + "." + encode(principalDigest) + "."
                + orgId + "." + encode(argumentsDigest) + "." + expiresAt.getEpochSecond();
        String signature = sign(payload);
        String token = payload + "." + signature;
        return new ConfirmationToken(token, operationId, principalDigest, orgId,
                argumentsDigest, signature, expiresAt, false);
    }

    @Override
    public ConfirmationToken verify(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("invalid confirmation token");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 6) {
            throw new IllegalArgumentException("invalid confirmation token");
        }
        String payload = String.join(".", parts[0], parts[1], parts[2], parts[3], parts[4]);
        byte[] expected = sign(payload).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = parts[5].getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("invalid confirmation token signature");
        }
        try {
            return new ConfirmationToken(token, decode(parts[0]), decode(parts[1]),
                    Long.parseLong(parts[2]), decode(parts[3]), parts[5],
                    Instant.ofEpochSecond(Long.parseLong(parts[4])), false);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid confirmation token claims", e);
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("unable to sign confirmation token", e);
        }
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }
}
