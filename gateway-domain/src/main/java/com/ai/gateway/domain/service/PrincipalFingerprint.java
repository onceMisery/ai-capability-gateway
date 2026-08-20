package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.Principal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/** Stable digest of every Principal field that can affect authorization scope. */
public final class PrincipalFingerprint {

    private PrincipalFingerprint() {
    }

    public static String digest(Principal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        List<String> roles = principal.roles().stream().sorted().toList();
        List<String> permissions = principal.permissions().stream().sorted().toList();
        return Sha256Digest.sha256Hex(canonical(List.of(
                principal.subject(), Long.toString(principal.orgId()),
                String.join("\u001f", roles), String.join("\u001f", permissions))));
    }

    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static String canonical(List<String> values) {
        StringBuilder canonical = new StringBuilder();
        for (String value : values) {
            String nonNull = Objects.requireNonNull(value,
                    "principal digest value must not be null");
            canonical.append(nonNull.getBytes(StandardCharsets.UTF_8).length)
                    .append(':').append(nonNull).append(';');
        }
        return canonical.toString();
    }
}
