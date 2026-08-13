-- V3__execution_audit.sql
-- Execution record, operation record, audit event, outbox event, and NL interaction tables.
-- Covers: write-operation two-phase protocol (Section 13), audit events (Section 19.3),
-- transactional outbox (Section 16.3), and clarification sessions (Section 9.5).

CREATE TABLE execution_record (
    execution_id VARCHAR(64) PRIMARY KEY,
    principal_digest VARCHAR(64) NOT NULL,
    org_id BIGINT NOT NULL,
    capability_id VARCHAR(256) NOT NULL,
    capability_version VARCHAR(32) NOT NULL,
    snapshot_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    routing_summary JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_execution_status ON execution_record(status);

CREATE TABLE operation_record (
    operation_id VARCHAR(64) PRIMARY KEY,
    state VARCHAR(32) NOT NULL,
    principal_digest VARCHAR(64) NOT NULL,
    org_id BIGINT NOT NULL,
    capability_id VARCHAR(256) NOT NULL,
    capability_version VARCHAR(32) NOT NULL,
    manifest_digest VARCHAR(64) NOT NULL,
    snapshot_version BIGINT NOT NULL,
    encrypted_arguments TEXT,
    arguments_digest VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    policy_decision_id VARCHAR(64),
    confirmation_summary JSONB,
    expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_operation_state ON operation_record(state);
CREATE INDEX idx_operation_idempotency ON operation_record(idempotency_key);

CREATE TABLE audit_event (
    event_id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    subject_digest VARCHAR(64),
    org_id BIGINT,
    request_id VARCHAR(64),
    operation_id VARCHAR(64),
    capability_id VARCHAR(256),
    capability_version VARCHAR(32),
    manifest_digest VARCHAR(64),
    snapshot_version BIGINT,
    policy_decision_id VARCHAR(64),
    model_prompt_version VARCHAR(64),
    result_code VARCHAR(64),
    duration_ms BIGINT,
    details JSONB
);
CREATE INDEX idx_audit_event_type ON audit_event(event_type);
CREATE INDEX idx_audit_timestamp ON audit_event(timestamp);

CREATE TABLE outbox_event (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    exported_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_status ON outbox_event(status);

CREATE TABLE nl_interaction (
    interaction_id VARCHAR(64) PRIMARY KEY,
    principal_digest VARCHAR(64) NOT NULL,
    snapshot_version BIGINT NOT NULL,
    candidates JSONB NOT NULL,
    confirmed_params JSONB,
    pending_fields JSONB,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_interaction_expires ON nl_interaction(expires_at);
