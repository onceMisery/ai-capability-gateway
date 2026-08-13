package com.ai.gateway.adapter.dubbo;

import com.ai.gateway.domain.model.AttachmentWhitelist;
import com.ai.gateway.domain.model.SystemContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Attachment whitelist manager for Dubbo invocation context.
 *
 * <p>Allowed attachments use the platform whitelist defined in
 * {@link AttachmentWhitelist}:</p>
 * <ul>
 * <li>{@code traceId} - the distributed trace identifier</li>
 * <li>{@code deadline} - the request deadline for downstream timeout propagation</li>
 * <li>{@code locale} - the request locale</li>
 * <li>{@code delegatedToken} - a short-lived, audience-bound delegated token</li>
 * <li>{@code b3-traceid} - B3 trace propagation header</li>
 * <li>{@code b3-spanid} - B3 span propagation header</li>
 * <li>{@code rtid} - the trace user identifier key for logging only;
 * never participates in authorization</li>
 * </ul>
 *
 * <p>Manifests MUST NOT define arbitrary attachment names. Unsigned tenant,
 * user, or permission attachments do not participate in authorization. The
 * manager does NOT introduce an internal Dubbo Filter ecosystem: no
 * dependency on internal filter JARs, internal attachment keys, or implicit
 * call chain contracts.</p>
 *
 * @since 0.1.0
 */
@Component
public class DubboAttachmentManager {

    private static final Logger log = LoggerFactory.getLogger(DubboAttachmentManager.class);

    /**
     * The attachment key for the distributed trace identifier.
     */
    private static final String ATTACHMENT_TRACE_ID = "traceId";

    /**
     * The attachment key for the request deadline.
     */
    private static final String ATTACHMENT_DEADLINE = "deadline";

    /**
     * The attachment key for the request locale.
     */
    private static final String ATTACHMENT_LOCALE = "locale";

    /**
     * The attachment key for the delegated token.
     */
    private static final String ATTACHMENT_DELEGATED_TOKEN = "delegatedToken";

    /**
     * The attachment key for B3 trace ID.
     */
    private static final String ATTACHMENT_B3_TRACEID = "b3-traceid";

    /**
     * The attachment key for B3 span ID.
     */
    private static final String ATTACHMENT_B3_SPANID = "b3-spanid";

    /**
     * The attachment key for the trace user identifier (logging only).
     */
    private static final String ATTACHMENT_RTID = "rtid";

    /**
     * Constructs a new DubboAttachmentManager.
     */
    public DubboAttachmentManager() {
        log.info("DubboAttachmentManager initialized");
    }

    /**
     * Builds the Dubbo attachment map from the system context, filtered by
     * the platform attachment whitelist.
     *
     * <p>Only whitelisted attachment keys are included. The values are
     * derived from {@link SystemContext}:</p>
     * <ul>
     * <li>{@code traceId} ← {@code systemContext.traceId()}</li>
     * <li>{@code deadline} ← {@code String.valueOf(systemContext.deadlineEpochMs())}</li>
     * <li>{@code locale} ← {@code systemContext.locale()}</li>
     * <li>{@code b3-traceid} ← {@code systemContext.traceId()} (B3 propagation)</li>
     * <li>{@code b3-spanid} ← a new UUID-based span ID</li>
     * <li>{@code rtid} ← {@code systemContext.traceId()} (logging only)</li>
     * </ul>
     *
     * <p>The {@code delegatedToken} is not set by this manager from
     * {@link SystemContext}; it requires an authentication context that is
     * not available at the attachment building stage.</p>
     *
     * @param systemContext the platform execution context
     * @param whitelist the attachment whitelist (enforces the closed set)
     * @return a map of whitelisted attachment keys to string values; never null
     * @throws NullPointerException if systemContext or whitelist is null
     */
    public Map<String, String> buildAttachments(SystemContext systemContext,
                                                AttachmentWhitelist whitelist) {
        Objects.requireNonNull(systemContext, "systemContext must not be null");
        Objects.requireNonNull(whitelist, "whitelist must not be null");

        Set<String> allowedKeys = AttachmentWhitelist.allowedKeys();
        Map<String, String> attachments = new HashMap<>();

        // traceId
        if (allowedKeys.contains(ATTACHMENT_TRACE_ID)) {
            attachments.put(ATTACHMENT_TRACE_ID, systemContext.traceId());
        }

        // deadline
        if (allowedKeys.contains(ATTACHMENT_DEADLINE)) {
            attachments.put(ATTACHMENT_DEADLINE,
                    String.valueOf(systemContext.deadlineEpochMs()));
        }

        // locale
        if (allowedKeys.contains(ATTACHMENT_LOCALE)) {
            attachments.put(ATTACHMENT_LOCALE, systemContext.locale());
        }

        // b3-traceid (B3 trace propagation — same as traceId)
        if (allowedKeys.contains(ATTACHMENT_B3_TRACEID)) {
            attachments.put(ATTACHMENT_B3_TRACEID, systemContext.traceId());
        }

        // b3-spanid (generate a new span ID for this hop)
        if (allowedKeys.contains(ATTACHMENT_B3_SPANID)) {
            String spanId = generateSpanId();
            attachments.put(ATTACHMENT_B3_SPANID, spanId);
        }

        // rtid (trace user identifier for logging only — never participates
        // in authorization)
        if (allowedKeys.contains(ATTACHMENT_RTID)) {
            attachments.put(ATTACHMENT_RTID, systemContext.traceId());
        }

        // delegatedToken — not available from SystemContext; requires
        // authentication context. Left unset here.
        // If a delegated token is configured via binding, it is injected
        // separately by the argument binder.

        log.debug("Built {} attachments from system context", attachments.size());
        return attachments;
    }

    /**
     * Generates a new span ID for B3 trace propagation.
     *
     * <p>Uses a shortened UUID (first 16 hex characters) to create a
     * unique span identifier for this invocation hop.</p>
     *
     * @return a new span ID string
     */
    private String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
