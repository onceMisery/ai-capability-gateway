-- V1__capability_manifest.sql
-- Capability Manifest, validation, and approval tables (Section 8.1 - 8.3).
-- The manifest lifecycle state machine: DRAFT -> VALIDATED -> APPROVED -> PUBLISHED -> SUSPENDED -> RETIRED.

CREATE TABLE capability_manifest (
    id VARCHAR(256) NOT NULL,
    version VARCHAR(32) NOT NULL,
    raw_content JSONB NOT NULL,
    sha256_digest VARCHAR(64) NOT NULL,
    owner_team VARCHAR(128) NOT NULL,
    owner_contact VARCHAR(256) NOT NULL,
    lifecycle VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, version)
);
CREATE INDEX idx_manifest_lifecycle ON capability_manifest(lifecycle);
CREATE INDEX idx_manifest_owner ON capability_manifest(owner_team);

CREATE TABLE capability_validation (
    manifest_id VARCHAR(256) NOT NULL,
    manifest_version VARCHAR(32) NOT NULL,
    report JSONB NOT NULL,
    validated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (manifest_id, manifest_version),
    FOREIGN KEY (manifest_id, manifest_version) REFERENCES capability_manifest(id, version)
);

CREATE TABLE capability_approval (
    manifest_id VARCHAR(256) NOT NULL,
    manifest_version VARCHAR(32) NOT NULL,
    approver VARCHAR(64) NOT NULL,
    role VARCHAR(64) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    summary JSONB NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (manifest_id, manifest_version)
);
