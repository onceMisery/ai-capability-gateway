package com.ai.gateway.domain.model;

/**
 * Controlled sources for protocol argument binding.
 *
 * <p>The gateway enforces a strict separation between model-visible and
 * model-invisible parameters. Only
 * {@link #MODEL} parameters appear in the public input Schema; all other
 * sources are injected deterministically by the gateway and cannot be
 * overridden by the model or the user.</p>
 *
 * <table border="1">
 * <caption>Argument sources and visibility</caption>
 * <tr><th>Source</th><th>Meaning</th><th>Model-visible</th></tr>
 * <tr><td>{@link #MODEL}</td><td>Business parameters from LLM output</td><td>Yes</td></tr>
 * <tr><td>{@link #PRINCIPAL}</td><td>From the authenticated Principal</td><td>No</td></tr>
 * <tr><td>{@link #CONSTANT}</td><td>From the confirmed Manifest constant</td><td>No</td></tr>
 * <tr><td>{@link #SYSTEM}</td><td>Platform context: traceId, deadline, idempotencyKey, locale</td><td>No</td></tr>
 * </table>
 *
 * <p>{@code SYSTEM} may only read platform-built-in whitelisted paths
 *. Manifests cannot declare new system variables.</p>
 *
 * @see ArgumentBinding
 * @see FieldBinding
 * @see AttachmentBinding
 * @since 0.1.0
 */
public enum ArgumentSource {
    /**
     * Value read from the model's structured output. Only MODEL-sourced
     * parameters (or MODEL leaf fields in composite bindings) may appear
     * in the public input Schema.
     */
    MODEL,

    /**
     * Value read from the authenticated Principal, such as
     * orgId. Never model-visible; never acceptable from request body,
     * query parameters, or custom headers.
     */
    PRINCIPAL,

    /**
     * Value read from a constant declared in the confirmed Manifest.
     * Not model-visible.
     */
    CONSTANT,

    /**
     * Value read from the execution context's platform-whitelisted paths:
     * {@code /traceId}, {@code /deadlineEpochMs}, {@code /idempotencyKey},
     * {@code /locale}. Manifests cannot declare new system
     * variables.
     */
    SYSTEM
}
