package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.ModelDecision;
import com.ai.gateway.domain.model.ErrorCode;

import java.util.List;
import java.util.Map;

/**
 * Port for structured LLM-based capability selection during natural-language
 * routing.
 *
 * <p>(LLM Security Boundary) specifies that the gateway
 * accesses model providers through a unified {@code LlmRouterPort}.
 * Business code must not directly depend on proprietary SDKs. The port
 * uses structured output or Function Calling, and the gateway performs
 * local Schema validation on the final JSON.</p>
 *
 * <p>Specifies that the model receives only the Top-K
 * authorized candidate capabilities — each represented by a short alias
 * (e.g., {@code cap_7k3m2v6p4a9d1f8q}), public description, and public
 * input Schema. The model must return exactly one of three decision types
 *: {@code SELECT}, {@code CLARIFY}, or {@code NO_MATCH}.</p>
 *
 * <p>Key constraints:</p>
 * <ul>
 * <li>Model requests contain only the authorized Top-K capabilities and
 * necessary user text — no protocol bindings, addresses, interface
 * names, or tenant identity.</li>
 * <li>Prompt templates, model IDs, temperature, and parser versions must
 * be versioned and enter evaluation.</li>
 * <li>Model provider responses must not be logged in full.</li>
 * <li>If the LLM is unavailable, the gateway returns a clear error or
 * routes to a manual entry — it must not degrade to guessing
 * interfaces.</li>
 * </ul>
 *
 * <p>Adapters implementing this port call the model provider's API. The
 * port is a pure abstraction with no framework dependencies.</p>
 *
 * @see ModelDecision
 * @see LlmCandidate
 * @since 0.1.0
 */
public interface LlmRouterPort {

    /** Stable failure raised by an LLM adapter without leaking provider details. */
    final class LlmRoutingException extends RuntimeException {
        private final ErrorCode errorCode;

        public LlmRoutingException(ErrorCode errorCode, String message) {
            super(message);
            this.errorCode = java.util.Objects.requireNonNull(errorCode);
        }

        public LlmRoutingException(ErrorCode errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = java.util.Objects.requireNonNull(errorCode);
        }

        public ErrorCode errorCode() {
            return errorCode;
        }
    }

    /**
     * Routes the user's natural-language text to a capability selection
     * decision using the LLM.
     *
     * <p>: the model receives the authorized Top-K candidates,
     * each with a short alias and public description. It must return one
     * of three decisions: {@link ModelDecision.SelectDecision SELECT},
     * {@link ModelDecision.ClarifyDecision CLARIFY}, or
     * {@link ModelDecision.NoMatchDecision NO_MATCH}.</p>
     *
     * <p>The gateway performs deterministic checks after the model returns
     *: alias belongs to the candidate set, Principal still
     * has permission, capability version is still executable, arguments
     * satisfy the JSON Schema, non-model parameters are injected from
     * trusted sources, and the risk level permits the current execution
     * mode.</p>
     *
     * @param userText the user's natural-language request text
     * @param candidates the authorized Top-K candidate capabilities with
     * short aliases and public descriptions
     * @return the model's routing decision; never {@code null}
     */
    ModelDecision route(String userText, List<LlmCandidate> candidates);

    /**
     * A candidate capability presented to the LLM during routing.
     *
     * <p>: the model only receives the short alias, public
     * display name, description, examples (positive/negative/synonyms),
     * and the public input Schema. It does not receive protocol bindings,
     * service addresses, interface class names, tenant identity,
     * serialization methods, timeout, or retry configuration.</p>
     *
     * <p>The {@code alias} is a short, collision-resistant identifier
     * generated per request as
     * {@code cap_<base32(sha256(snapshotVersion + capabilityId + version))[0:16]>},
     * avoiding dot/colon issues and length limits. The
     * {@code inputSchema} contains only MODEL-source business fields.</p>
     *
     * @param alias the short alias identifying the candidate
     * @param displayName the user-facing capability name
     * @param description the single business-action description
     * @param positiveExamples the positive examples (queries this capability handles)
     * @param negativeExamples the negative examples (queries it does not handle)
     * @param synonyms the key noun synonyms
     * @param inputSchema the public input Schema (MODEL fields only)
     */
    record LlmCandidate(
            String alias,
            String displayName,
            String description,
            List<String> positiveExamples,
            List<String> negativeExamples,
            List<String> synonyms,
            Map<String, Object> inputSchema
    ) {
    }
}
