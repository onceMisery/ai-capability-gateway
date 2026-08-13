-- V2__catalog_snapshot.sql
-- Catalog snapshot and snapshot item tables (Section 8.4 - 8.5).
-- Publication produces a monotonically increasing snapshot_version; snapshots are immutable once published.

CREATE TABLE catalog_snapshot (
    snapshot_version BIGSERIAL PRIMARY KEY,
    environment VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    digest VARCHAR(64) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM'
);
CREATE INDEX idx_snapshot_env_status ON catalog_snapshot(environment, status);

CREATE TABLE catalog_snapshot_item (
    snapshot_version BIGINT NOT NULL,
    capability_id VARCHAR(256) NOT NULL,
    capability_version VARCHAR(32) NOT NULL,
    manifest_digest VARCHAR(64) NOT NULL,
    policy_ref VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (snapshot_version, capability_id, capability_version),
    FOREIGN KEY (snapshot_version) REFERENCES catalog_snapshot(snapshot_version)
);
