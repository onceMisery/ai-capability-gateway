-- V4__acl_and_console.sql
-- Admin console support: capability ACL, roles, permissions.
-- Covers: capability-level authorization (Section 15), role-based permission
-- management, and audit indexes for the console read-side queries.

-- ================================================================
-- Permission words: domain:resource:action convention
-- ================================================================
CREATE TABLE gateway_permission (
    name VARCHAR(128) PRIMARY KEY,
    description VARCHAR(512) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ================================================================
-- Roles: named groups of permissions
-- ================================================================
CREATE TABLE gateway_role (
    name VARCHAR(64) PRIMARY KEY,
    description VARCHAR(512) NOT NULL DEFAULT '',
    permissions JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ================================================================
-- Capability ACL entries: capability -> allowed roles mapping
-- ================================================================
CREATE TABLE capability_acl (
    capability_id VARCHAR(256) NOT NULL,
    capability_version VARCHAR(32) NOT NULL,
    allowed_roles JSONB NOT NULL DEFAULT '[]'::jsonb,
    required_permissions JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) NOT NULL DEFAULT 'system',
    PRIMARY KEY (capability_id, capability_version)
);

-- ================================================================
-- Additional indexes for admin console read-side queries
-- ================================================================
CREATE INDEX idx_audit_capability ON audit_event(capability_id);
CREATE INDEX idx_audit_request ON audit_event(request_id);
CREATE INDEX idx_audit_result_code ON audit_event(result_code);

-- ================================================================
-- Console settings (key-value store for admin console preferences)
-- ================================================================
CREATE TABLE console_setting (
    setting_key VARCHAR(128) PRIMARY KEY,
    setting_value TEXT NOT NULL,
    description VARCHAR(512) NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) NOT NULL DEFAULT 'system'
);

-- ================================================================
-- Seed default roles (use specific permission names, no wildcards)
-- ================================================================
INSERT INTO gateway_role (name, description, permissions) VALUES
    ('admin', 'Full administrative access',
     '["system:config:read","system:config:write","system:monitor:read","system:audit:read","capability:manifest:import","capability:manifest:approve","capability:release:publish","capability:release:rollback","capability:suspend:emergency","acl:role:manage","acl:permission:manage","acl:entry:manage"]'::jsonb),
    ('operator', 'Operational monitoring and management',
     '["system:config:read","system:monitor:read","system:audit:read"]'::jsonb),
    ('developer', 'Capability development and testing',
     '["system:capability:read","system:capability:write","capability:manifest:import"]'::jsonb);
