package com.ai.gateway.domain.port;

import java.time.Instant;
import java.util.List;

/**
 * Port for the Transactional Outbox pattern.
 *
 * <p>(Consistency) specifies that publication, operation
 * state changes, and audit index writes are committed in a local
 * transaction. A Transactional Outbox is used to export events to the
 * message system or SIEM, avoiding the scenario where the state commit
 * succeeds but the audit notification is lost.</p>
 *
 * <p>The {@code operation_record} table uses version numbers and
 * conditional updates to prevent duplicate execution. The active
 * snapshot is immutable; only new snapshots can be created. The
 * in-instance index must record its snapshotVersion and expose it in
 * health checks.</p>
 *
 * <p>(Dependency Failure Strategy): if the audit export is
 * unavailable, the local Outbox accumulates and alerts; events must not
 * be dropped. The terminal state write must not be downgraded to a
 * lossy async operation for performance.</p>
 *
 * <p>Adapters implementing this port manage the Outbox table in
 * PostgreSQL and provide export workers. The port is a pure abstraction
 * with no framework dependencies.</p>
 *
 * @see OutboxEvent
 * @since 0.1.0
 */
public interface OutboxPort {

    /**
     * Publishes an event to the Outbox for asynchronous export.
     *
     * <p>: the event is written in the same local transaction
     * as the state change, ensuring at-least-once delivery to the
     * downstream message system or SIEM. The payload is a controlled
     * JSON string that must not contain stacks, internal addresses, or
     * sensitive parameters.</p>
     *
     * @param eventType the event type (e.g., "REQUEST_ACCEPTED", "STARTED", "SUCCEEDED")
     * @param payloadJson the controlled JSON payload
     */
    void publish(String eventType, String payloadJson);

    /**
     * Polls unexported Outbox events for processing by an export worker.
     *
     * <p> the export worker polls the Outbox
     * table for events that have not yet been marked as exported. If the
     * downstream system is unavailable, events accumulate locally and an
     * alert is raised; events are never dropped.</p>
     *
     * @param batchSize the maximum number of events to poll
     * @return the list of unexported events, ordered by creation time;
     * never {@code null}
     */
    List<OutboxEvent> pollUnexported(int batchSize);

    /**
     * Marks an Outbox event as successfully exported.
     *
     * <p>: after the downstream system confirms receipt, the
     * event is marked as exported to prevent re-processing. Idempotent
     * consumers on the downstream side handle at-least-once delivery
     * semantics.</p>
     *
     * @param eventId the Outbox event ID to mark as exported
     */
    void markExported(long eventId);

    /**
     * An Outbox event awaiting or completed export.
     *
     * <p>: each event represents a state change, audit record,
     * or notification that must be exported to the downstream message
     * system or SIEM via the Transactional Outbox pattern.</p>
     *
     * @param id the unique event identifier
     * @param eventType the event type
     * @param payloadJson the controlled JSON payload
     * @param createdAt the event creation timestamp
     */
    record OutboxEvent(long id, String eventType, String payloadJson, Instant createdAt) {
    }
}
