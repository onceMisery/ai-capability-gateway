export type CapabilityLifecycle =
  | 'DRAFT'
  | 'VALIDATED'
  | 'APPROVED'
  | 'PUBLISHED'
  | 'SUSPENDED'
  | 'RETIRED'
  | 'REJECTED'

export type RiskLevel = 'READ_ONLY' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL' | string

export interface PrincipalInfo {
  subject?: string
  userId?: string
  username?: string
  orgId?: number
  roles: string[]
  permissions: string[]
  authMethod?: string
}

export interface AuthCapabilities {
  authProvider: 'stub' | 'sa-token' | string
  consoleEnabled: boolean
  loginMode: string
}

export interface LoginResult {
  token: string
  refreshToken: string
  expiresInSeconds: number
  refreshExpiresInSeconds: number
  principal: PrincipalInfo
}

export interface CapabilitySummary {
  capabilityId: string
  version: string
  displayName: string
  description: string
  risk: RiskLevel
  lifecycle: CapabilityLifecycle
  tags: string[]
  ownerTeam: string
  ownerContact: string
  sha256Digest: string
  updatedAt: string
  snapshotVersions: number[]
}

export interface CapabilityManifest {
  apiVersion: string
  kind: string
  metadata: {
    id: string
    version: string
    owner: { team: string; contact: string }
    tags?: string[]
  }
  spec: {
    displayName: string
    description: string
    examples: {
      positive: string[]
      negative: string[]
      synonyms: string[]
    }
    risk: RiskLevel
    inputSchema: Record<string, unknown>
    authorization?: {
      permissions: string[]
      principalClaims?: Record<string, unknown>
    }
    invocation: Record<string, unknown>
    output: Record<string, unknown>
    resilience: Record<string, unknown>
  }
}

export interface ValidationReport {
  valid: boolean
  errors: string[]
  warnings: string[]
}

export interface ManifestMutationResult {
  manifestDigest?: string
  validationReport?: ValidationReport
  capabilityId?: string
  capabilityVersion?: string
  riskLevel?: string
  snapshotVersion?: number
  newSnapshotVersion?: number
}

export interface SnapshotSummary {
  snapshotVersion: number
  environment: string
  status: 'ACTIVE' | 'SUPERSEDED' | string
  digest: string
  capabilityCount: number
  publishedAt: string
  publishedBy: string
}

export interface SnapshotDetail {
  snapshotVersion: number
  environment: string
  policyRef: string
  digest: string
  capabilityCount: number
  capabilities: Array<{ id: string; version: string }>
}

export interface AuditEvent {
  eventId: string
  eventType: string
  timestamp: string
  subjectDigest?: string
  orgId?: number
  requestId?: string
  operationId?: string
  capabilityId?: string
  capabilityVersion?: string
  manifestDigest?: string
  snapshotVersion?: number
  policyDecisionId?: string
  modelPromptVersion?: string
  resultCode?: string
  durationMs?: number
  detailsJson?: string
}

export interface AuditPage {
  items: AuditEvent[]
  total: number
}

export interface AuditQuery {
  eventType?: string
  capabilityId?: string
  requestId?: string
  resultCode?: string
  from?: number
  to?: number
  page?: number
  size?: number
}

export type NaturalLanguageQueryStatus =
  | 'COMPLETED'
  | 'CLARIFICATION_REQUIRED'
  | 'NO_MATCH'
  | 'ERROR'
  | 'INVALID'
  | string

export interface NaturalLanguageQueryResult {
  requestId?: string
  status: NaturalLanguageQueryStatus
  data?: unknown
  summary?: string
  question?: string
  message?: string
  errorCode?: string
  interactionId?: string
  snapshotVersion?: number
  capability?: unknown
  execution?: unknown
  expiresAt?: string
}

export interface CapabilityStat {
  capability_id: string
  total_calls: number
  success_count: number
  failure_count: number
  avg_duration_ms?: number
}

export interface TimeSeriesPoint {
  time: number
  resultCode: string
  count: number
}

export interface HealthStatus {
  status: 'UP' | 'DOWN' | string
  checks: Record<string, 'UP' | 'DOWN' | string>
}

export interface Role {
  name: string
  description: string
  permissions: string[]
  createdAt: string
  updatedAt: string
}

export interface Permission {
  name: string
  description: string
  createdAt: string
}

export interface CapabilityAclEntry {
  capabilityId: string
  capabilityVersion: string
  allowedRoles: string[]
  requiredPermissions: string[]
  updatedAt: string
  updatedBy: string
}

export interface AclPolicy {
  aclLoadHealthy: boolean
  aclEntryCount: number
  emptyAclDecision: 'ALLOW' | 'DENY'
  aclEntries: CapabilityAclEntry[]
  roles: Role[]
  permissions: Permission[]
}

export interface GatewayConfig {
  environment: string
  authProvider: string
  cacheProvider: string
  ratelimitProvider: string
  maxRequestSizeBytes: number
  maxResponseBytes: number
  defaultTimeoutMs: number
  rateLimits: Record<string, unknown>
  auditConfig: Record<string, unknown>
  snapshotConfig: Record<string, unknown>
  sentinelConfig: Record<string, unknown>
}

export interface CacheStatus {
  provider: string
  redisAddress: string
  localTtlSeconds: number
  currentSnapshotVersion: number
  lastRefreshTimestamp: number
}

export interface RateLimitRule {
  type: 'flow' | 'degrade'
  resource: string
  properties: Record<string, unknown>
}
