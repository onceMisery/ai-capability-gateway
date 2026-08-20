package com.ai.gateway.application.agent;

import com.ai.gateway.application.catalog.ActiveCatalogView;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.Principal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Issues and verifies short-lived, principal-bound opaque capability references. */
public final class ToolReferenceService {

    private static final String VERSION = "tr1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;
    private static final int OPAQUE_TAG_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int MAX_TOKEN_LENGTH = 2048;

    private final String currentKeyId;
    private final Map<String, byte[]> keys;
    private final long ttlSeconds;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Map<IndexKey, Map<String, CapabilityManifest>> capabilityIndexes =
            new ConcurrentHashMap<>();

    public ToolReferenceService(String currentKeyId,
                                byte[] currentKey,
                                String previousKeyId,
                                byte[] previousKey,
                                long ttlSeconds) {
        this(currentKeyId, currentKey, previousKeyId, previousKey,
                ttlSeconds, Clock.systemUTC(), new SecureRandom());
    }

    ToolReferenceService(String currentKeyId,
                         byte[] currentKey,
                         String previousKeyId,
                         byte[] previousKey,
                         long ttlSeconds,
                         Clock clock,
                         SecureRandom secureRandom) {
        this.currentKeyId = requireTokenPart(currentKeyId, "currentKeyId");
        this.ttlSeconds = requireRange(ttlSeconds, 10, 600, "ttlSeconds");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");

        Map<String, byte[]> configuredKeys = new LinkedHashMap<>();
        configuredKeys.put(this.currentKeyId, requireKey(currentKey, "currentKey"));
        if (previousKeyId != null && !previousKeyId.isBlank()) {
            String keyId = requireTokenPart(previousKeyId, "previousKeyId");
            if (keyId.equals(this.currentKeyId)) {
                throw new IllegalArgumentException("previousKeyId must differ from currentKeyId");
            }
            configuredKeys.put(keyId, requireKey(previousKey, "previousKey"));
        }
        this.keys = Map.copyOf(configuredKeys);
    }

    public IssuedReference issue(Principal principal,
                                 CapabilityManifest manifest,
                                 long catalogVersion,
                                 long policyEpoch) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(manifest, "manifest must not be null");
        requirePositive(catalogVersion, "catalogVersion");
        requirePositive(policyEpoch, "policyEpoch");

        byte[] key = keys.get(currentKeyId);
        String capabilityKey = capabilityKey(key, manifest, catalogVersion);
        String principalTag = principalTag(key, principal);
        long expiresAt = clock.instant().plusSeconds(ttlSeconds).getEpochSecond();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);

        String unsigned = String.join(".", VERSION, currentKeyId, capabilityKey,
                principalTag, Long.toString(catalogVersion), Long.toString(policyEpoch),
                Long.toString(expiresAt), encode(nonce));
        String toolRef = unsigned + "." + encode(hmac(key, unsigned));
        return new IssuedReference(toolRef, Instant.ofEpochSecond(expiresAt));
    }

    public Verification verify(String toolRef,
                               Principal principal,
                               ActiveCatalogView view,
                               long currentPolicyEpoch) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(view, "view must not be null");
        if (toolRef == null || toolRef.isBlank() || toolRef.length() > MAX_TOKEN_LENGTH) {
            return Verification.failure(Failure.MALFORMED);
        }

        String[] parts = toolRef.split("\\.", -1);
        if (parts.length != 9 || !VERSION.equals(parts[0])) {
            return Verification.failure(Failure.MALFORMED);
        }
        byte[] key = keys.get(parts[1]);
        if (key == null) {
            return Verification.failure(Failure.SIGNATURE_INVALID);
        }

        String unsigned = String.join(".", Arrays.copyOf(parts, 8));
        byte[] suppliedSignature;
        try {
            suppliedSignature = decode(parts[8]);
        } catch (IllegalArgumentException e) {
            return Verification.failure(Failure.MALFORMED);
        }
        if (!MessageDigest.isEqual(hmac(key, unsigned), suppliedSignature)) {
            return Verification.failure(Failure.SIGNATURE_INVALID);
        }

        long catalogVersion;
        long policyEpoch;
        long expiresAt;
        try {
            catalogVersion = Long.parseLong(parts[4]);
            policyEpoch = Long.parseLong(parts[5]);
            expiresAt = Long.parseLong(parts[6]);
            decode(parts[7]);
        } catch (IllegalArgumentException e) {
            return Verification.failure(Failure.MALFORMED);
        }
        if (clock.instant().getEpochSecond() > expiresAt) {
            return Verification.failure(Failure.EXPIRED);
        }
        if (catalogVersion != view.catalogVersion()) {
            return Verification.failure(Failure.CATALOG_CHANGED);
        }
        if (policyEpoch <= 0 || policyEpoch != currentPolicyEpoch) {
            return Verification.failure(Failure.POLICY_CHANGED);
        }
        if (!MessageDigest.isEqual(
                decodeUtf8(principalTag(key, principal)), decodeUtf8(parts[3]))) {
            return Verification.failure(Failure.PRINCIPAL_MISMATCH);
        }

        CapabilityManifest manifest = capabilityIndex(parts[1], key, view).get(parts[2]);
        if (manifest == null) {
            return Verification.failure(Failure.CAPABILITY_UNAVAILABLE);
        }
        return Verification.valid(manifest, catalogVersion, policyEpoch,
                Instant.ofEpochSecond(expiresAt));
    }

    private Map<String, CapabilityManifest> capabilityIndex(
            String keyId, byte[] key, ActiveCatalogView view) {
        IndexKey indexKey = new IndexKey(keyId, view.catalogVersion());
        Map<String, CapabilityManifest> existing = capabilityIndexes.get(indexKey);
        if (existing != null) {
            return existing;
        }

        Map<String, CapabilityManifest> built = new LinkedHashMap<>();
        for (CapabilityManifest manifest : view.capabilities()) {
            String opaqueKey = capabilityKey(key, manifest, view.catalogVersion());
            if (built.put(opaqueKey, manifest) != null) {
                throw new IllegalStateException("opaque capability key collision");
            }
        }
        if (capabilityIndexes.size() >= 4) {
            capabilityIndexes.clear();
        }
        Map<String, CapabilityManifest> immutable = Map.copyOf(built);
        capabilityIndexes.put(indexKey, immutable);
        return immutable;
    }

    private static String capabilityKey(
            byte[] key, CapabilityManifest manifest, long catalogVersion) {
        String canonical = canonical(List.of(
                manifest.metadata().id(), manifest.metadata().version(),
                Long.toString(catalogVersion)));
        return encode(Arrays.copyOf(hmac(key, "capability\n" + canonical), OPAQUE_TAG_BYTES));
    }

    private static String principalTag(byte[] key, Principal principal) {
        List<String> roles = principal.roles().stream().sorted().toList();
        List<String> permissions = principal.permissions().stream().sorted().toList();
        String canonical = canonical(List.of(
                principal.subject(), Long.toString(principal.orgId()),
                String.join("\u001f", roles), String.join("\u001f", permissions)));
        return encode(Arrays.copyOf(hmac(key, "principal\n" + canonical), OPAQUE_TAG_BYTES));
    }

    private static String canonical(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            String nonNull = Objects.requireNonNull(value, "canonical value must not be null");
            result.append(nonNull.getBytes(StandardCharsets.UTF_8).length)
                    .append(':').append(nonNull).append(';');
        }
        return result.toString();
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC is unavailable", e);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static byte[] decodeUtf8(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] requireKey(byte[] key, String name) {
        if (key == null || key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(name + " must contain at least 32 bytes");
        }
        return key.clone();
    }

    private static String requireTokenPart(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException(name + " must be a URL-safe token part");
        }
        return value;
    }

    private static long requireRange(long value, long min, long max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public record IssuedReference(String toolRef, Instant expiresAt) {
    }

    public enum Failure {
        MALFORMED,
        SIGNATURE_INVALID,
        EXPIRED,
        PRINCIPAL_MISMATCH,
        CATALOG_CHANGED,
        POLICY_CHANGED,
        CAPABILITY_UNAVAILABLE
    }

    public record Verification(
            boolean valid,
            Failure failure,
            CapabilityManifest manifest,
            long catalogVersion,
            long policyEpoch,
            Instant expiresAt) {

        private static Verification valid(
                CapabilityManifest manifest,
                long catalogVersion,
                long policyEpoch,
                Instant expiresAt) {
            return new Verification(true, null, manifest, catalogVersion, policyEpoch, expiresAt);
        }

        private static Verification failure(Failure failure) {
            return new Verification(false, failure, null, 0L, 0L, null);
        }
    }

    private record IndexKey(String keyId, long catalogVersion) {
    }
}
