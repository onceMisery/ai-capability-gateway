-- Control-plane consistency and audit retention support.

ALTER TABLE outbox_event
    ADD COLUMN audit_event_id BIGINT REFERENCES audit_event(event_id);

CREATE INDEX idx_outbox_audit_event_id ON outbox_event(audit_event_id);
CREATE INDEX idx_execution_created_at ON execution_record(created_at);
CREATE INDEX idx_execution_capability_created
    ON execution_record(capability_id, created_at DESC);
