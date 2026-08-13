package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.domain.model.EnvelopeConfig;
import com.ai.gateway.domain.model.EnvelopeProfile;
import com.ai.gateway.domain.port.EnvelopeProfileRegistry;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link EnvelopeProfileRegistry}.
 *
 * <p>(Response Contract) specifies that the gateway cannot
 * depend on business unified-response Java JARs. Each unified-response
 * structure type must be registered as a standard envelope profile and
 * validated against the real Provider during compatibility testing.</p>
 *
 * <p>This implementation is initialized with the platform standard profile
 * ({@code code="200"}, {@code dataPath=/value}, {@code messagePath=/message}).
 * Additional profiles can be registered at runtime via {@link #register}.</p>
 *
 * <p>Uses a {@link ConcurrentHashMap} for thread-safe access. This
 * implementation is suitable for single-instance deployments and testing.
 * For multi-instance consistency, a database-backed implementation would
 * be required.</p>
 *
 * @see EnvelopeProfileRegistry
 * @since 0.1.0
 */
@Repository
public class JdbcEnvelopeProfileRegistry implements EnvelopeProfileRegistry {

    private static final String PLATFORM_STANDARD = "platform-standard";

    private final ConcurrentHashMap<String, EnvelopeProfile> profiles = new ConcurrentHashMap<>();

    /**
     * Constructs a new JdbcEnvelopeProfileRegistry pre-loaded with the
     * platform standard envelope profile.
     */
    public JdbcEnvelopeProfileRegistry() {
        EnvelopeConfig standardConfig = new EnvelopeConfig(
                "/code",
                List.of("200"),
                "/value",
                "/message");
        EnvelopeProfile standardProfile = new EnvelopeProfile(
                PLATFORM_STANDARD,
                standardConfig,
                "Platform standard unified-response envelope: code=/code (200), data=/value, message=/message");
        profiles.put(PLATFORM_STANDARD, standardProfile);
    }

    @Override
    public Optional<EnvelopeProfile> findByName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return Optional.ofNullable(profiles.get(name));
    }

    @Override
    public void register(EnvelopeProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        profiles.put(profile.name(), profile);
    }
}
