package com.ai.gateway.domain.port;

/**
 * Exports transactional outbox events to an external durable sink.
 * Implementations must return only after the downstream system has accepted
 * the event; throwing leaves the event pending for retry.
 */
public interface OutboxExporterPort {

    void export(OutboxPort.OutboxEvent event) throws Exception;
}
