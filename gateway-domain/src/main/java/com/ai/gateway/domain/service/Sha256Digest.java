package com.ai.gateway.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared SHA-256 digest utility.
 *
 * <p>Centralizes the SHA-256 hex-digest logic that previously existed in
 * multiple use cases and services. The digest is used for manifest content
 * integrity, principal subject pseudonymization, and snapshot integrity
 * verification.</p>
 *
 * <p>A new {@link MessageDigest} instance is created per call to ensure
 * thread safety, since {@code MessageDigest} is not thread-safe.</p>
 *
 * <p>SHA-256 is mandated by the JCA specification for every Java runtime;
 * a failure to obtain the algorithm is treated as a platform-level error and
 * thrown as {@link IllegalStateException} rather than silently producing a
 * placeholder digest.</p>
 *
 * @since 0.1.0
 */
public final class Sha256Digest {

    private Sha256Digest() {
        // Utility class — not instantiable
    }

    /**
     * Computes the hex-encoded SHA-256 digest of the given UTF-8 content.
     *
     * @param content the content to digest; must not be null
     * @return the lowercase hex-encoded SHA-256 digest
     * @throws NullPointerException if {@code content} is null
     * @throws IllegalStateException if SHA-256 is unavailable (should never
     * happen on a standard JVM)
     */
    public static String sha256Hex(String content) {
        return HexFormat.of().formatHex(sha256(content));
    }

    /**
     * Computes the raw SHA-256 digest bytes of the given UTF-8 content.
     *
     * @param content the content to digest; must not be null
     * @return the 32-byte SHA-256 digest
     * @throws NullPointerException if {@code content} is null
     * @throws IllegalStateException if SHA-256 is unavailable (should never
     * happen on a standard JVM)
     */
    public static byte[] sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
