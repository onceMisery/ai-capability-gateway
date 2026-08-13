package com.ai.gateway.domain.model;

/**
 * Determines how the adapter interprets the protocol response before
 * projection and redaction.
 *
 * <p>Defines two modes:</p>
 * <ul>
 * <li>{@link #ENVELOPE} - The response has a wrapper structure with a
 * success code, data, and optional message. The envelope config
 * must declare {@code codePath}, {@code successValues}, and
 * {@code dataPath}.</li>
 * <li>{@link #DIRECT} - The protocol return value's root node is the
 * data directly. No success-code path is declared.</li>
 * </ul>
 *
 * @see OutputContract
 * @see EnvelopeConfig
 * @since 0.1.0
 */
public enum OutputMode {
    /**
     * Envelope mode. The response must be unwrapped using the configured
     * codePath, successValues, and dataPath before projection.
     */
    ENVELOPE,

    /**
     * Direct mode. The protocol return root node is treated as the data
     * without envelope unwrapping. No success-code path is declared.
     */
    DIRECT
}
