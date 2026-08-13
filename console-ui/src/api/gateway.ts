import type { AxiosRequestConfig } from 'axios'
import { request, unwrapResponse } from '@/utils/request'
import type {
  AclPolicy,
  AuditPage,
  AuditQuery,
  AuthCapabilities,
  CacheStatus,
  CapabilityAclEntry,
  CapabilityManifest,
  CapabilityStat,
  CapabilitySummary,
  GatewayConfig,
  HealthStatus,
  LoginResult,
  ManifestMutationResult,
  Permission,
  PrincipalInfo,
  RateLimitRule,
  Role,
  SnapshotDetail,
  SnapshotSummary,
  TimeSeriesPoint
} from '@/types/gateway'

async function get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const response = await request.get(url, config)
  return unwrapResponse<T>(response.data)
}

async function post<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const response = await request.post(url, body, config)
  return unwrapResponse<T>(response.data)
}

async function put<T>(url: string, body?: unknown): Promise<T> {
  const response = await request.put(url, body)
  return unwrapResponse<T>(response.data)
}

async function remove<T>(url: string): Promise<T> {
  const response = await request.delete(url)
  return unwrapResponse<T>(response.data)
}

export const gatewayApi = {
  authCapabilities: () => get<AuthCapabilities>('/admin/v1/console/auth/capabilities'),
  login: (username: string, password: string) =>
    post<LoginResult>('/admin/v1/console/auth/login', { username, password }),
  whoami: () => get<PrincipalInfo>('/admin/v1/console/auth/whoami'),
  logout: (accessToken: string) => post<{ message: string }>(
    '/admin/v1/console/auth/logout',
    undefined,
    { headers: { Authorization: `Bearer ${accessToken}` } }
  ),

  capabilities: () => get<CapabilitySummary[]>('/admin/v1/capabilities'),
  capability: (id: string, version: string) =>
    get<CapabilityManifest>(`/admin/v1/capabilities/${encodeURIComponent(id)}/versions/${encodeURIComponent(version)}`),
  importManifest: (manifest: CapabilityManifest) =>
    post<ManifestMutationResult>('/admin/v1/manifests:import', manifest),
  validateCapability: (id: string, version: string) =>
    post<ManifestMutationResult>(`/admin/v1/capabilities/${encodeURIComponent(id)}/versions/${encodeURIComponent(version)}:validate`),
  approveCapability: (id: string, version: string) =>
    post<ManifestMutationResult>(`/admin/v1/capabilities/${encodeURIComponent(id)}/versions/${encodeURIComponent(version)}:approve`, {}),
  suspendCapability: (id: string, reason: string) =>
    post<ManifestMutationResult>(`/admin/v1/capabilities/${encodeURIComponent(id)}:suspend`, { reason }),

  snapshots: (environment: string, limit = 50) =>
    get<SnapshotSummary[]>('/admin/v1/releases', { params: { environment, limit } }),
  snapshot: (version: number) => get<SnapshotDetail>(`/admin/v1/releases/${version}`),
  publish: (
    environment: string,
    capabilities?: Array<{ capabilityId: string; version: string }>
  ) =>
    post<ManifestMutationResult>('/admin/v1/releases:publish', {
      environment,
      capabilities
    }),
  rollback: (targetSnapshotVersion: number, environment: string) =>
    post<ManifestMutationResult>('/admin/v1/releases:rollback', { targetSnapshotVersion, environment }),

  audits: (query: AuditQuery) => get<AuditPage>('/admin/v1/audits', { params: query }),
  capabilityStats: (from: number, to: number) =>
    get<CapabilityStat[]>('/admin/v1/stats/capabilities', { params: { from, to } }),
  timeSeries: (from: number, to: number) =>
    get<TimeSeriesPoint[]>('/admin/v1/stats/time-series', { params: { from, to } }),
  readiness: () =>
    get<HealthStatus>('/health/readiness', {
      validateStatus: (status) => status === 200 || status === 503
    }),

  aclPolicy: () => get<AclPolicy>('/admin/v1/acl/policy'),
  aclEntries: () => get<CapabilityAclEntry[]>('/admin/v1/acl/entries'),
  saveAcl: (entry: Pick<CapabilityAclEntry, 'capabilityId' | 'capabilityVersion' | 'allowedRoles'>, updatedBy: string) =>
    put<{ message: string }>(
      `/admin/v1/acl/entries/${encodeURIComponent(entry.capabilityId)}/${encodeURIComponent(entry.capabilityVersion)}`,
      { allowedRoles: entry.allowedRoles, updatedBy }
    ),
  deleteAcl: (capabilityId: string, version: string) =>
    remove<{ message: string }>(`/admin/v1/acl/entries/${encodeURIComponent(capabilityId)}/${encodeURIComponent(version)}`),
  roles: () => get<Role[]>('/admin/v1/roles'),
  saveRole: (role: Pick<Role, 'name' | 'description' | 'permissions'>, update = false) =>
    update
      ? put<{ message: string }>(`/admin/v1/roles/${encodeURIComponent(role.name)}`, role)
      : post<{ message: string }>('/admin/v1/roles', role),
  deleteRole: (name: string) => remove<{ message: string }>(`/admin/v1/roles/${encodeURIComponent(name)}`),
  permissions: () => get<Permission[]>('/admin/v1/permissions'),
  savePermission: (permission: Pick<Permission, 'name' | 'description'>) =>
    post<{ message: string }>('/admin/v1/permissions', permission),
  deletePermission: (name: string) =>
    remove<{ message: string }>(`/admin/v1/permissions/${encodeURIComponent(name)}`),

  config: () => get<GatewayConfig>('/admin/v1/config'),
  cacheStatus: () => get<CacheStatus>('/admin/v1/cache/status'),
  rateLimitRules: () => get<RateLimitRule[]>('/admin/v1/ratelimit/rules')
}
