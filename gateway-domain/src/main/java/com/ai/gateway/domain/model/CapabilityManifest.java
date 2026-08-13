package com.ai.gateway.domain.model;

import java.util.List;
import java.util.Map;

/**
 * The complete, machine-verifiable Capability Manifest that transforms a
 * governed microservice API into a natural-language-discoverable capability.
 *
 * <p>Defines the top-level structure. The Manifest uses YAML
 * or JSON format and is validated by a versioned JSON Schema.
 * Markdown is only for supplementary human-readable documentation and is
 * not an executable contract.</p>
 *
 * <p>Each Manifest has a stable {@code metadata.id}, semantic version,
 * and content SHA-256 digest. The same
 * {@code metadata.id + version} content cannot be overwritten; modifications
 * must produce a new version.</p>
 *
 * <p>The Manifest contains only protocol type-name strings; the gateway
 * does not load the {@code interfaceName}, {@code parameterTypes}, or any
 * business API class at compile or runtime.</p>
 *
 * <p>Lifecycle state, confirmation records, publication environment, and
 * snapshot version are control-plane records and are not self-declared in
 * the Manifest.</p>
 *
 * @param apiVersion the Manifest specification version (e.g., "gateway.ai/v1")
 * @param kind always "Capability"
 * @param metadata the capability metadata (ID, version, owner, tags)
 * @param spec the capability specification
 * @since 0.1.0
 */
public record CapabilityManifest(
        String apiVersion,
        String kind,
        Metadata metadata,
        Spec spec
) {

    /**
     * Compact constructor performing null checks.
     *
     * @param apiVersion the API version
     * @param kind the kind
     * @param metadata the metadata
     * @param spec the spec
     */
    public CapabilityManifest {
        java.util.Objects.requireNonNull(apiVersion, "apiVersion must not be null");
        java.util.Objects.requireNonNull(kind, "kind must not be null");
        java.util.Objects.requireNonNull(metadata, "metadata must not be null");
        java.util.Objects.requireNonNull(spec, "spec must not be null");
    }

    /**
     * The metadata block identifying the capability, its version, and its
     * responsible team.
     *
     * <p> {@code id} uses the
     * {@code domain.resource.action} convention (e.g.,
     * {@code order.detail.query}) and only allows lowercase letters, digits,
     * dots, and hyphens. Once published, the ID cannot be renamed.</p>
     *
     * @param id the globally stable capability identifier
     * @param version the SemVer version string
     * @param owner the responsible team and contact
     * @param tags the controlled tags (optional)
     */
    public record Metadata(
            String id,
            String version,
            Owner owner,
            List<String> tags
    ) {

        /**
         * Compact constructor performing defensive copying.
         *
         * @param id the capability ID
         * @param version the version
         * @param owner the owner
         * @param tags the tags
         */
        public Metadata {
            java.util.Objects.requireNonNull(id, "id must not be null");
            java.util.Objects.requireNonNull(version, "version must not be null");
            java.util.Objects.requireNonNull(owner, "owner must not be null");
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /**
     * The responsible team and contact for the capability.
     *
     * @param team the team name (e.g., "order-platform")
     * @param contact the contact email (e.g., "order-platform@example.com")
     */
    public record Owner(
            String team,
            String contact
    ) {

        /**
         * Compact constructor performing null checks.
         *
         * @param team the team name
         * @param contact the contact email
         */
        public Owner {
            java.util.Objects.requireNonNull(team, "team must not be null");
            java.util.Objects.requireNonNull(contact, "contact must not be null");
        }
    }

    /**
     * The capability specification containing all execution-relevant
     * configuration.
     *
     * <p>Defines the following required fields:</p>
     * <ul>
     * <li>{@code displayName} - user-understandable capability name.</li>
     * <li>{@code description} - single business-action description.</li>
     * <li>{@code examples} - positive, negative, and disambiguation examples.</li>
     * <li>{@code risk} - the risk level governing execution mode.</li>
     * <li>{@code inputSchema} - model-visible JSON Schema 2020-12.</li>
     * <li>{@code authorization} - required permissions and principal claims
     * (optional in the initial release).</li>
     * <li>{@code invocation} - protocol binding and deterministic parameter
     * mapping.</li>
     * <li>{@code output} - response unwrapping, projection, redaction, and
     * Schema.</li>
     * <li>{@code resilience} - timeout, retry, concurrency, and circuit
     * breaker.</li>
     * </ul>
     *
     * @param displayName the user-facing capability name
     * @param description the single business-action description
     * @param examples the positive, negative, and synonym examples
     * @param risk the risk level
     * @param inputSchema the model-visible JSON Schema
     * @param authorization the required permissions and principal claims
     * @param invocation the protocol binding
     * @param output the output contract
     * @param resilience the resilience policy
     */
    public record Spec(
            String displayName,
            String description,
            Examples examples,
            RiskLevel risk,
            Map<String, Object> inputSchema,
            Authorization authorization,
            ProtocolBinding invocation,
            OutputContract output,
            ResiliencePolicy resilience
    ) {

        /**
         * Compact constructor performing defensive copying.
         *
         * @param displayName the display name
         * @param description the description
         * @param examples the examples
         * @param risk the risk level
         * @param inputSchema the input schema
         * @param authorization the authorization
         * @param invocation the invocation binding
         * @param output the output contract
         * @param resilience the resilience policy
         */
        public Spec {
            java.util.Objects.requireNonNull(displayName, "displayName must not be null");
            java.util.Objects.requireNonNull(description, "description must not be null");
            java.util.Objects.requireNonNull(examples, "examples must not be null");
            java.util.Objects.requireNonNull(risk, "risk must not be null");
            java.util.Objects.requireNonNull(inputSchema, "inputSchema must not be null");
            java.util.Objects.requireNonNull(invocation, "invocation must not be null");
            java.util.Objects.requireNonNull(output, "output must not be null");
            java.util.Objects.requireNonNull(resilience, "resilience must not be null");
            inputSchema = Map.copyOf(inputSchema);
        }
    }

    /**
     * Natural-language examples for retrieval and model routing.
     *
     * <p>Requires at least:</p>
     * <ul>
     * <li>Three positive examples.</li>
     * <li>Two negative examples identifying confusing alternatives.</li>
     * <li>Key noun synonyms.</li>
     * </ul>
     *
     * <p>Examples participate in BM25 retrieval and model
     * routing. They are governed production configuration confirmed by the
     * business Owner.</p>
     *
     * @param positive the positive examples (queries this capability handles)
     * @param negative the negative examples (queries it does not handle)
     * @param synonyms the key noun synonyms
     */
    public record Examples(
            List<String> positive,
            List<String> negative,
            List<String> synonyms
    ) {

        /**
         * Compact constructor performing defensive copying.
         *
         * @param positive the positive examples
         * @param negative the negative examples
         * @param synonyms the synonyms
         */
        public Examples {
            java.util.Objects.requireNonNull(positive, "positive must not be null");
            java.util.Objects.requireNonNull(negative, "negative must not be null");
            java.util.Objects.requireNonNull(synonyms, "synonyms must not be null");
            positive = List.copyOf(positive);
            negative = List.copyOf(negative);
            synonyms = List.copyOf(synonyms);
        }
    }

    /**
     * The authorization requirements for invoking the capability.
     *
     * <p> {@code permissions} uses the
     * {@code domain:resource:action} three-segment convention.
     * Wildcards are prohibited.</p>
     *
     * <p>{@code principalClaims} defines required Principal claims (e.g.,
     * {@code /orgId} must be an integer and required). The gateway validates
     * these before parameter binding.</p>
     *
     * <p>In the initial release, authorization is optional:
     * all authenticated users may call all published read-only capabilities.
     * This design is preserved for when the capability surface grows or
     * write operations are enabled.</p>
     *
     * @param permissions the required permission strings
     * @param principalClaims the required Principal claims keyed by JSON Pointer
     */
    public record Authorization(
            List<String> permissions,
            Map<String, ClaimRequirement> principalClaims
    ) {

        /**
         * Compact constructor performing defensive copying.
         *
         * @param permissions the permission strings
         * @param principalClaims the principal claim requirements
         */
        public Authorization {
            java.util.Objects.requireNonNull(permissions, "permissions must not be null");
            permissions = List.copyOf(permissions);
            principalClaims = principalClaims == null ? Map.of() : Map.copyOf(principalClaims);
        }
    }

    /**
     * A requirement for a single Principal claim.
     *
     * <p> defines the expected type and whether
     * the claim is mandatory. The gateway validates Principal claims before
     * parameter binding.</p>
     *
     * @param type the expected claim type (e.g., "integer", "string")
     * @param required whether the claim must be present and non-null
     */
    public record ClaimRequirement(
            String type,
            boolean required
    ) {

        /**
         * Compact constructor performing null check.
         *
         * @param type the claim type
         * @param required whether the claim is required
         */
        public ClaimRequirement {
            java.util.Objects.requireNonNull(type, "type must not be null");
        }
    }
}
