package com.ai.gateway.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * Generates short aliases for candidate capabilities during natural-language
 * routing.
 *
 * <p>Function or tool names must not directly use the real capabilityId to
 * avoid point, colon, and length limitations, as well as collisions. The
 * gateway generates a short alias per request:</p>
 * <pre>
 * cap_&lt;base32(sha256(snapshotVersion + capabilityId + version))[0:16]&gt;
 * </pre>
 *
 * <p>The alias is a function-name-safe string prefixed with {@code cap_},
 * followed by the first 16 characters of the RFC 4648 Base32 encoding
 * (no padding) of the SHA-256 digest of the concatenation of the snapshot
 * version, capability ID, and semantic version.</p>
 *
 * <p>If a collision is detected (the same alias was already generated for
 * a different capability in the same request), the digest length is
 * increased — the alias takes more characters from the Base32-encoded
 * digest until a unique alias is found. Overwriting an existing alias is
 * prohibited.</p>
 *
 * <p>The model only receives the alias, the public description, and the
 * public input Schema — never the protocol Binding or real capabilityId.</p>
 *
 * <p>This class is thread-safe: each call creates its own
 * {@link MessageDigest} instance (which is not thread-safe) and performs
 * no shared mutable state.</p>
 *
 * @since 0.1.0
 */
public final class AliasGenerator {

    /**
     * The prefix for all generated aliases.
     */
    private static final String ALIAS_PREFIX = "cap_";

    /**
     * The default number of Base32 characters to take from the digest.
     */
    private static final int DEFAULT_DIGEST_LENGTH = 16;

    /**
     * The maximum number of Base32 characters available from a SHA-256
     * digest (256 bits = 32 bytes = 52 Base32 characters without padding;
     * 52 is the practical ceiling).
     */
    private static final int MAX_DIGEST_LENGTH = 52;

    /**
     * RFC 4648 Base32 encoding alphabet (no padding character).
     */
    private static final char[] BASE32_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    /**
     * Bit mask for extracting 5-bit groups.
     */
    private static final int FIVE_BIT_MASK = 0x1F;

    /**
     * Generates a short alias for the given capability within a request
     * context.
     *
     * <p>If the generated alias collides with one already present in
     * {@code existingAliases}, the digest length is increased until a
     * unique alias is produced. The maximum supported length is 52
     * Base32 characters; if all lengths are exhausted and still colliding,
     * an exception is thrown (this is practically impossible with SHA-256).</p>
     *
     * @param snapshotVersion the monotonically increasing catalog snapshot version
     * @param capabilityId the capability identifier (e.g., "order.detail.query")
     * @param version the semantic version string (e.g., "1.0.0")
     * @param existingAliases the set of aliases already generated in this
     * request context; may be empty but not null
     * @return a unique alias string prefixed with {@code cap_}
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if a unique alias cannot be generated
     * within the maximum digest length
     */
    public String generate(long snapshotVersion, String capabilityId,
                           String version, Set<String> existingAliases) {
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(version, "version must not be null");
        java.util.Objects.requireNonNull(existingAliases, "existingAliases must not be null");

        byte[] digest = sha256(snapshotVersion + capabilityId + version);
        String base32Encoded = encodeBase32(digest);

        for (int length = DEFAULT_DIGEST_LENGTH; length <= MAX_DIGEST_LENGTH; length++) {
            String alias = ALIAS_PREFIX + base32Encoded.substring(0, length);
            if (!existingAliases.contains(alias)) {
                return alias;
            }
        }

        // Extremely unlikely: all possible alias lengths are taken
        throw new IllegalArgumentException(
                "Unable to generate a unique alias for capabilityId=" + capabilityId
                        + ", version=" + version
                        + "; all alias lengths up to " + MAX_DIGEST_LENGTH
                        + " are already in use"
        );
    }

    /**
     * Generates a short alias with the default digest length, without
     * collision checking.
     *
     * <p>This is a convenience method for callers that do not need collision
     * handling (e.g., single-candidate scenarios). For request-context alias
     * generation with multiple candidates, use
     * {@link #generate(long, String, String, Set)}.</p>
     *
     * @param snapshotVersion the catalog snapshot version
     * @param capabilityId the capability identifier
     * @param version the semantic version string
     * @return the alias string prefixed with {@code cap_}
     * @throws NullPointerException if {@code capabilityId} or {@code version} is null
     */
    public String generate(long snapshotVersion, String capabilityId, String version) {
        java.util.Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        java.util.Objects.requireNonNull(version, "version must not be null");

        byte[] digest = sha256(snapshotVersion + capabilityId + version);
        String base32Encoded = encodeBase32(digest);
        return ALIAS_PREFIX + base32Encoded.substring(0, DEFAULT_DIGEST_LENGTH);
    }

    /**
     * Computes the SHA-256 digest of the given input string.
     *
     * <p>A new {@link MessageDigest} instance is created per call to ensure
     * thread safety, since {@code MessageDigest} is not thread-safe.</p>
     *
     * @param input the input string
     * @return the 32-byte SHA-256 digest
     * @throws java.lang.InternalError if SHA-256 is not available (should never happen)
     */
    private byte[] sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Encodes the given bytes using RFC 4648 Base32 encoding without
     * padding.
     *
     * <p>Each group of 5 bits is mapped to one character in the Base32
     * alphabet {@code ABCDEFGHIJKLMNOPQRSTUVWXYZ234567}. The output has
     * no {@code =} padding characters.</p>
     *
     * @param bytes the bytes to encode
     * @return the Base32-encoded string without padding
     */
    private String encodeBase32(byte[] bytes) {
        StringBuilder result = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;

        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                int index = (buffer >> bitsLeft) & FIVE_BIT_MASK;
                result.append(BASE32_ALPHABET[index]);
            }
        }

        // Handle remaining bits (less than 5)
        if (bitsLeft > 0) {
            int index = (buffer << (5 - bitsLeft)) & FIVE_BIT_MASK;
            result.append(BASE32_ALPHABET[index]);
        }

        return result.toString();
    }
}
