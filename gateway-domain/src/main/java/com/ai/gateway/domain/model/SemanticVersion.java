package com.ai.gateway.domain.model;

import java.util.regex.Pattern;

/**
 * Semantic version string for a capability manifest.
 *
 * <p>Specifies the versioning rules:</p>
 * <ul>
 * <li>Major version bump: removing public fields, tightening field
 * constraints, changing semantics or protocol parameter positions.</li>
 * <li>Minor version bump: adding optional fields or compatible output
 * fields.</li>
 * <li>Patch version bump: modifying examples or descriptions without
 * changing selection semantics.</li>
 * </ul>
 *
 * <p>Any change to the protocol Binding requires re-running compatibility
 * tests. Only one default-routable version per {@code metadata.id} may
 * exist in a given environment at a time.</p>
 *
 * <p>This record validates the SemVer format ({@code MAJOR.MINOR.PATCH})
 * but does not enforce the bump rules — those are policy decisions made
 * during compatibility analysis.</p>
 *
 * @param value the semantic version string (e.g., {@code "1.0.0"})
 * @since 0.1.0
 */
public record SemanticVersion(String value) {

    /**
     * Pattern matching SemVer {@code MAJOR.MINOR.PATCH} format with
     * optional pre-release and build metadata suffixes.
     */
    private static final Pattern SEMVER_PATTERN =
            Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)"
                    + "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*)))?"
                    + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

    /**
     * Compact constructor performing SemVer format validation.
     *
     * @param value the semantic version string
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code value} does not conform
     * to the SemVer format
     */
    public SemanticVersion {
        java.util.Objects.requireNonNull(value, "version value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("version value must not be blank");
        }
        if (!SEMVER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "version value must conform to SemVer MAJOR.MINOR.PATCH format: " + value);
        }
    }
}
