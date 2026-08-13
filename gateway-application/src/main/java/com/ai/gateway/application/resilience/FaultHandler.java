package com.ai.gateway.application.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Dependency fault strategy handler.
 *
 * <p>This class maps dependency fault types to the appropriate fault
 * action, implementing the gateway's fault tolerance policy. The policy
 * distinguishes between three response strategies:</p>
 *
 * <ul>
 * <li><strong>{@link FaultAction#FAIL_CLOSED}</strong> — the request is
 * rejected immediately. No data is returned, no fallback is attempted.
 * Used for security-critical failures where proceeding would risk
 * data leakage or unauthorized access.</li>
 * <li><strong>{@link FaultAction#DEGRADE_GRACEFULLY}</strong> — the
 * gateway continues operating with reduced functionality. Cached
 * data or in-memory state is used where possible, but new operations
 * that depend on the failed dependency are rejected.</li>
 * <li><strong>{@link FaultAction#ALERT_AND_CONTINUE}</strong> — the
 * gateway continues normal operation but emits an alert. Data is
 * retained locally (e.g., in an Outbox backlog) for later export
 * when the dependency recovers. No data is ever discarded.</li>
 * </ul>
 *
 * <p><strong>Fault-to-action mapping:</strong></p>
 * <table border="1">
 * <caption>Fault handling matrix</caption>
 * <tr><th>Fault Type</th><th>Action</th><th>Behavior</th></tr>
 * <tr><td>POSTGRESQL_UNAVAILABLE</td><td>DEGRADE_GRACEFULLY</td>
 * <td>Keep loaded snapshots but stop new calls</td></tr>
 * <tr><td>LLM_UNAVAILABLE</td><td>DEGRADE_GRACEFULLY</td>
 * <td>Return routing unavailable, don't invoke Provider</td></tr>
 * <tr><td>REGISTRY_UNAVAILABLE</td><td>DEGRADE_GRACEFULLY</td>
 * <td>Existing References continue, new creation fails</td></tr>
 * <tr><td>SNAPSHOT_LAG</td><td>DEGRADE_GRACEFULLY</td>
 * <td>Exit readiness after threshold</td></tr>
 * <tr><td>AUDIT_EXPORT_UNAVAILABLE</td><td>ALERT_AND_CONTINUE</td>
 * <td>Local Outbox backlog + alert, never discard</td></tr>
 * <tr><td>REDACTION_FAILURE</td><td>FAIL_CLOSED</td>
 * <td>Block result return, record controlled error</td></tr>
 * <tr><td>AUTHORIZATION_FAILURE</td><td>FAIL_CLOSED</td>
 * <td>Deny request, no fallback</td></tr>
 * <tr><td>BINDING_FAILURE</td><td>FAIL_CLOSED</td>
 * <td>Reject request, no partial binding</td></tr>
 * </table>
 *
 * <p>Specifies that authorization, binding, redaction, and
 * write-state failures MUST Fail Closed. Audit export failures MUST NOT
 * discard data — the local Outbox retains events until the SIEM recovers.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * It is stateless and thread-safe.</p>
 *
 * @since 0.1.0
 */
public final class FaultHandler {

    private static final Logger log = LoggerFactory.getLogger(FaultHandler.class);

    /**
     * Constructs a new FaultHandler.
     */
    public FaultHandler() {
        // Stateless — no dependencies required
    }

    /**
     * Determines the appropriate fault action for the given fault type
     *
     * @param faultType the type of dependency fault that occurred
     * @return the action to take in response to the fault
     * @throws NullPointerException if {@code faultType} is null
     */
    public FaultAction handleFault(FaultType faultType) {
        Objects.requireNonNull(faultType, "faultType must not be null");

        FaultAction action = switch (faultType) {
            case POSTGRESQL_UNAVAILABLE -> FaultAction.DEGRADE_GRACEFULLY;
            case LLM_UNAVAILABLE -> FaultAction.DEGRADE_GRACEFULLY;
            case REGISTRY_UNAVAILABLE -> FaultAction.DEGRADE_GRACEFULLY;
            case SNAPSHOT_LAG -> FaultAction.DEGRADE_GRACEFULLY;
            case AUDIT_EXPORT_UNAVAILABLE -> FaultAction.ALERT_AND_CONTINUE;
            case REDACTION_FAILURE -> FaultAction.FAIL_CLOSED;
            case AUTHORIZATION_FAILURE -> FaultAction.FAIL_CLOSED;
            case BINDING_FAILURE -> FaultAction.FAIL_CLOSED;
        };

        log.warn("Fault handled: type={}, action={}", faultType, action);
        return action;
    }

    /**
     * The type of dependency fault that occurred.
     */
    public enum FaultType {
        /**
         * PostgreSQL is unavailable. The gateway keeps loaded snapshots in
         * memory but stops accepting new calls that require database access.
         */
        POSTGRESQL_UNAVAILABLE,

        /**
         * The LLM service is unavailable. Natural-language routing is
         * disabled; the gateway does not invoke the Provider for NL queries.
         * Deterministic execution may continue for already-routed requests.
         */
        LLM_UNAVAILABLE,

        /**
         * The service registry (e.g., Dubbo registry) is unavailable.
         * Existing References continue to work, but new reference creation
         * fails.
         */
        REGISTRY_UNAVAILABLE,

        /**
         * The instance's catalog snapshot is behind the publication threshold.
         * The instance should exit the ready state after exceeding the
         * maximum lag time.
         */
        SNAPSHOT_LAG,

        /**
         * The audit export pipeline (Outbox to SIEM) is unavailable.
         * Events are retained in the local Outbox backlog; an alert is
         * emitted. No audit data is ever discarded.
         */
        AUDIT_EXPORT_UNAVAILABLE,

        /**
         * A redaction or output validation failure occurred. The result
         * must not be returned to the client. A controlled error is
         * recorded instead.
         */
        REDACTION_FAILURE,

        /**
         * An authorization check failed. The request is denied with no
         * fallback. Fail Closed is mandatory for all authorization
         * failures.
         */
        AUTHORIZATION_FAILURE,

        /**
         * A parameter binding failure occurred. The request is rejected;
         * no partial binding is attempted. Fail Closed is mandatory
         */
        BINDING_FAILURE
    }

    /**
     * The action to take in response to a dependency fault.
     */
    public enum FaultAction {
        /**
         * Reject the request immediately. No data is returned, no fallback
         * is attempted. Used for security-critical failures where proceeding
         * would risk data leakage or unauthorized access.
         */
        FAIL_CLOSED,

        /**
         * Continue operating with reduced functionality. Cached data or
         * in-memory state is used where possible, but new operations that
         * depend on the failed dependency are rejected.
         */
        DEGRADE_GRACEFULLY,

        /**
         * Continue normal operation but emit an alert. Data is retained
         * locally (e.g., in an Outbox backlog) for later export when the
         * dependency recovers. No data is ever discarded.
         */
        ALERT_AND_CONTINUE
    }
}
