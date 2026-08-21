import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'

interface RetriableRequestConfig extends InternalAxiosRequestConfig {
  _gatewayRetry?: boolean
  _gatewaySessionGeneration?: number
  _gatewayAccessToken?: string
  _gatewayRefreshToken?: string
}

interface StoredSessionSnapshot {
  accessToken: string
  refreshToken: string
  generation: number
}

interface RefreshedSession {
  token: string
  generation: number
  refreshToken: string
}

interface StoredRefreshRotationProof {
  predecessorDigest: string
  successorDigest: string
  completedAt: number
}

const TOKEN_KEY = 'console_token'
const REFRESH_TOKEN_KEY = 'console_refresh_token'
const EXPIRES_AT_KEY = 'console_expires_at'
const REFRESH_ROTATION_KEY = 'console_refresh_rotation'
const REFRESH_LOCK_NAME = 'ai-capability-gateway:refresh-session'
const REFRESH_ROTATION_PROOF_MAX_AGE_MS = 30_000
const NO_WEB_LOCK_ROTATION_WAIT_MS = 1_000

export const SESSION_UPDATED_EVENT = 'gateway-session-updated'

let sessionGeneration = 0
let refreshPromise: Promise<RefreshedSession> | null = null

function storedSessionIdentity(): string {
  return `${localStorage.getItem(TOKEN_KEY) || ''}\u0000${localStorage.getItem(REFRESH_TOKEN_KEY) || ''}`
}

let observedSessionIdentity = storedSessionIdentity()

function synchronizeStoredSession(): void {
  const nextIdentity = storedSessionIdentity()
  if (nextIdentity === observedSessionIdentity) return
  observedSessionIdentity = nextIdentity
  sessionGeneration += 1
  refreshPromise = null
}

function captureStoredSession(): StoredSessionSnapshot {
  synchronizeStoredSession()
  return {
    accessToken: localStorage.getItem(TOKEN_KEY) || '',
    refreshToken: localStorage.getItem(REFRESH_TOKEN_KEY) || '',
    generation: sessionGeneration
  }
}

function isCurrentSession(snapshot: StoredSessionSnapshot): boolean {
  const current = captureStoredSession()
  return current.generation === snapshot.generation
    && current.accessToken === snapshot.accessToken
    && current.refreshToken === snapshot.refreshToken
}

window.addEventListener('storage', (event) => {
  if (event.storageArea && event.storageArea !== localStorage) return
  if (event.key === null || event.key === TOKEN_KEY || event.key === REFRESH_TOKEN_KEY) {
    synchronizeStoredSession()
  }
})

export class GatewayApiError extends Error {
  readonly errorCode: string
  readonly traceId?: string
  readonly requestId?: string
  readonly status?: number

  constructor(message: string, options: {
    errorCode?: string
    traceId?: string
    requestId?: string
    status?: number
  } = {}) {
    super(message)
    this.name = 'GatewayApiError'
    this.errorCode = options.errorCode || 'REQUEST_FAILED'
    this.traceId = options.traceId
    this.requestId = options.requestId
    this.status = options.status
  }
}

export const request = axios.create({
  baseURL: '',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

const refreshClient = axios.create({
  baseURL: '',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' }
})

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

async function sha256(value: string): Promise<string | null> {
  if (!crypto.subtle) return null
  try {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))
    return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
  } catch {
    return null
  }
}

async function createRotationProof(
  predecessorRefreshToken: string,
  successorAccessToken: string,
  successorRefreshToken: string
): Promise<StoredRefreshRotationProof | null> {
  const [predecessorDigest, successorDigest] = await Promise.all([
    sha256(predecessorRefreshToken),
    sha256(`${successorAccessToken}\u0000${successorRefreshToken}`)
  ])
  if (!predecessorDigest || !successorDigest) return null
  return { predecessorDigest, successorDigest, completedAt: Date.now() }
}

function readRotationProof(): StoredRefreshRotationProof | null {
  try {
    const value = JSON.parse(localStorage.getItem(REFRESH_ROTATION_KEY) || 'null')
    if (!isRecord(value)) return null
    const proof = {
      predecessorDigest: String(value.predecessorDigest || ''),
      successorDigest: String(value.successorDigest || ''),
      completedAt: Number(value.completedAt || 0)
    }
    if (!proof.predecessorDigest || !proof.successorDigest
      || Date.now() - proof.completedAt > REFRESH_ROTATION_PROOF_MAX_AGE_MS) {
      localStorage.removeItem(REFRESH_ROTATION_KEY)
      return null
    }
    return proof
  } catch {
    localStorage.removeItem(REFRESH_ROTATION_KEY)
    return null
  }
}

async function refreshedSessionAfterRotation(snapshot: StoredSessionSnapshot): Promise<RefreshedSession | null> {
  const current = captureStoredSession()
  const hasRotated = current.accessToken !== snapshot.accessToken
    || current.refreshToken !== snapshot.refreshToken
  if (!hasRotated || !snapshot.refreshToken || !current.accessToken || !current.refreshToken) return null

  const proof = readRotationProof()
  if (!proof) return null
  const [predecessorDigest, successorDigest] = await Promise.all([
    sha256(snapshot.refreshToken),
    sha256(`${current.accessToken}\u0000${current.refreshToken}`)
  ])
  if (!predecessorDigest || !successorDigest
    || predecessorDigest !== proof.predecessorDigest
    || successorDigest !== proof.successorDigest
    || !isCurrentSession(current)) {
    return null
  }
  return {
    token: current.accessToken,
    refreshToken: current.refreshToken,
    generation: current.generation
  }
}

async function waitForRefreshedSession(snapshot: StoredSessionSnapshot): Promise<RefreshedSession | null> {
  const immediate = await refreshedSessionAfterRotation(snapshot)
  if (immediate) return immediate

  return new Promise((resolve) => {
    let settled = false
    let checking = false
    let pendingCheck = false
    let timer = 0

    const cleanup = () => {
      window.clearTimeout(timer)
      window.removeEventListener('storage', onStorage)
      window.removeEventListener(SESSION_UPDATED_EVENT, onSessionUpdated)
    }
    const finish = (session: RefreshedSession | null) => {
      if (settled) return
      settled = true
      cleanup()
      resolve(session)
    }
    const check = async () => {
      if (settled) return
      if (checking) {
        pendingCheck = true
        return
      }
      checking = true
      do {
        pendingCheck = false
        const session = await refreshedSessionAfterRotation(snapshot)
        if (session) {
          finish(session)
          break
        }
      } while (pendingCheck && !settled)
      checking = false
    }
    const onStorage = (event: StorageEvent) => {
      if (event.storageArea && event.storageArea !== localStorage) return
      if (event.key === null || event.key === TOKEN_KEY
        || event.key === REFRESH_TOKEN_KEY || event.key === REFRESH_ROTATION_KEY) {
        void check()
      }
    }
    const onSessionUpdated = () => { void check() }

    timer = window.setTimeout(() => finish(null), NO_WEB_LOCK_ROTATION_WAIT_MS)
    window.addEventListener('storage', onStorage)
    window.addEventListener(SESSION_UPDATED_EVENT, onSessionUpdated)
  })
}

export function unwrapResponse<T>(payload: unknown): T {
  if (isRecord(payload) && 'data' in payload && ('status' in payload || 'error' in payload)) {
    return payload.data as T
  }
  return payload as T
}

export function clearStoredSession(): void {
  sessionGeneration += 1
  refreshPromise = null
  for (const key of [
    TOKEN_KEY,
    REFRESH_TOKEN_KEY,
    EXPIRES_AT_KEY,
    REFRESH_ROTATION_KEY,
    'console_refresh_expires_at',
    'console_principal',
    'console_user',
    'console_roles',
    'console_perms'
  ]) {
    localStorage.removeItem(key)
  }
  observedSessionIdentity = storedSessionIdentity()
  window.dispatchEvent(new CustomEvent(SESSION_UPDATED_EVENT))
}

export function beginStoredSession(): void {
  sessionGeneration += 1
  refreshPromise = null
  localStorage.removeItem(REFRESH_ROTATION_KEY)
  observedSessionIdentity = storedSessionIdentity()
}

async function applyRefreshedSession(payload: unknown, expectedSession: StoredSessionSnapshot): Promise<RefreshedSession> {
  const data = unwrapResponse<Record<string, unknown>>(payload)
  const token = String(data.token || '')
  const refreshToken = String(data.refreshToken || '')
  const expiresInSeconds = Number(data.expiresInSeconds || 0)
  const refreshExpiresInSeconds = Number(data.refreshExpiresInSeconds || 0)

  if (!token || !refreshToken) {
    throw new GatewayApiError('会话刷新响应不完整', { errorCode: 'AUTHENTICATION_FAILED' })
  }
  if (!isCurrentSession(expectedSession)) {
    throw new GatewayApiError('会话已由其他操作更新', { errorCode: 'SESSION_SUPERSEDED' })
  }

  const rotationProof = await createRotationProof(expectedSession.refreshToken, token, refreshToken)
  if (!isCurrentSession(expectedSession)) {
    throw new GatewayApiError('会话已由其他操作更新', { errorCode: 'SESSION_SUPERSEDED' })
  }
  if (rotationProof) {
    localStorage.setItem(REFRESH_ROTATION_KEY, JSON.stringify(rotationProof))
  } else {
    localStorage.removeItem(REFRESH_ROTATION_KEY)
  }
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
  localStorage.setItem(EXPIRES_AT_KEY, String(Date.now() + expiresInSeconds * 1000))
  localStorage.setItem('console_refresh_expires_at', String(Date.now() + refreshExpiresInSeconds * 1000))
  observedSessionIdentity = storedSessionIdentity()
  window.dispatchEvent(new CustomEvent(SESSION_UPDATED_EVENT, { detail: { token } }))
  return { token, generation: sessionGeneration, refreshToken }
}

async function refreshAccessToken(expectedSession: StoredSessionSnapshot): Promise<RefreshedSession> {
  if (!expectedSession.refreshToken) {
    throw new GatewayApiError('登录已过期，请重新登录', { errorCode: 'AUTHENTICATION_FAILED' })
  }

  const refresh = async (): Promise<RefreshedSession> => {
    const rotatedSession = await refreshedSessionAfterRotation(expectedSession)
    if (rotatedSession) return rotatedSession
    if (!isCurrentSession(expectedSession)) {
      throw new GatewayApiError('会话已由其他操作更新', { errorCode: 'SESSION_SUPERSEDED' })
    }
    const response = await refreshClient.post('/admin/v1/console/auth/refresh', {
      refreshToken: expectedSession.refreshToken
    })
    return applyRefreshedSession(response.data, expectedSession)
  }

  if (navigator.locks?.request) {
    return navigator.locks.request(REFRESH_LOCK_NAME, refresh)
  }
  return refresh()
}

function requestSession(config: RetriableRequestConfig): StoredSessionSnapshot {
  return {
    accessToken: config._gatewayAccessToken || '',
    refreshToken: config._gatewayRefreshToken || '',
    generation: config._gatewaySessionGeneration ?? -1
  }
}

function retryWithSession(config: RetriableRequestConfig, session: RefreshedSession) {
  const snapshot: StoredSessionSnapshot = {
    accessToken: session.token,
    refreshToken: session.refreshToken,
    generation: session.generation
  }
  if (!isCurrentSession(snapshot)) {
    throw new GatewayApiError('会话已由其他操作更新', { errorCode: 'SESSION_SUPERSEDED' })
  }
  config.headers.Authorization = `Bearer ${session.token}`
  return request(config)
}

function normalizeError(error: AxiosError): GatewayApiError {
  const body = error.response?.data
  let errorCode = 'REQUEST_FAILED'
  let message = error.message || '请求失败'

  if (isRecord(body)) {
    const apiError = isRecord(body.error) ? body.error : undefined
    errorCode = String(apiError?.errorCode || body.errorCode || errorCode)
    message = String(apiError?.message || body.message || (typeof body.error === 'string' ? body.error : message))
  }

  return new GatewayApiError(message, {
    errorCode,
    status: error.response?.status,
    traceId: error.response?.headers?.['x-trace-id'],
    requestId: error.response?.headers?.['x-request-id']
  })
}

request.interceptors.request.use((config) => {
  const retriableConfig = config as RetriableRequestConfig
  const session = captureStoredSession()
  retriableConfig._gatewaySessionGeneration = session.generation
  retriableConfig._gatewayAccessToken = session.accessToken
  retriableConfig._gatewayRefreshToken = session.refreshToken
  if (session.accessToken) {
    config.headers.Authorization = `Bearer ${session.accessToken}`
  }
  const requestId = typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
  config.headers['X-Request-Id'] ||= `console-${requestId}`
  return config
})

request.interceptors.response.use(
  (response) => response,
  async (rawError: AxiosError) => {
    const config = rawError.config as RetriableRequestConfig | undefined
    const url = config?.url || ''
    const isLogoutRequest = url.includes('/console/auth/logout')
    let clearedCurrentSession = false
    const canRefresh = rawError.response?.status === 401
      && !!config
      && !config._gatewayRetry
      && !url.includes('/console/auth/login')
      && !isLogoutRequest
      && !url.includes('/console/auth/refresh')
      && !url.includes('/console/auth/capabilities')

    if (canRefresh) {
      config._gatewayRetry = true
      const attemptSession = requestSession(config)
      try {
        const rotatedSession = await refreshedSessionAfterRotation(attemptSession)
        if (rotatedSession) return retryWithSession(config, rotatedSession)
        if (!attemptSession.refreshToken) {
          throw new GatewayApiError('登录已过期，请重新登录', { errorCode: 'AUTHENTICATION_FAILED' })
        }
        if (!refreshPromise) {
          const pendingRefresh = refreshAccessToken(attemptSession)
          refreshPromise = pendingRefresh
          void pendingRefresh.then(
            () => { if (refreshPromise === pendingRefresh) refreshPromise = null },
            () => { if (refreshPromise === pendingRefresh) refreshPromise = null }
          )
        }
        const refreshedSession = await refreshPromise
        return retryWithSession(config, refreshedSession)
      } catch (error) {
        let rotatedSession = await refreshedSessionAfterRotation(attemptSession)
        const sessionStillCurrent = isCurrentSession(attemptSession)
        const isSuperseded = error instanceof GatewayApiError && error.errorCode === 'SESSION_SUPERSEDED'
        if (!rotatedSession && attemptSession.refreshToken && !navigator.locks?.request
          && sessionStillCurrent && !isSuperseded) {
          rotatedSession = await waitForRefreshedSession(attemptSession)
        }
        if (rotatedSession) return retryWithSession(config, rotatedSession)
        if (isCurrentSession(attemptSession)) {
          clearStoredSession()
          clearedCurrentSession = true
        }
        if (error instanceof GatewayApiError && error.errorCode === 'SESSION_SUPERSEDED') {
          return Promise.reject(error)
        }
      }
    }

    const requestBelongsToCurrentSession = !!config && isCurrentSession(requestSession(config))
    if (rawError.response?.status === 401
      && !isLogoutRequest
      && (requestBelongsToCurrentSession || clearedCurrentSession)) {
      if (!clearedCurrentSession) clearStoredSession()
      if (window.location.pathname !== '/login') {
        const redirect = encodeURIComponent(window.location.pathname + window.location.search)
        window.location.assign(`/login?redirect=${redirect}`)
      }
    }

    if (rawError.response?.status === 403 && url.startsWith('/admin/v1/')
      && window.location.pathname !== '/login' && window.location.pathname !== '/403') {
      const redirect = encodeURIComponent(window.location.pathname + window.location.search)
      window.location.assign(`/403?from=${redirect}`)
    }

    return Promise.reject(normalizeError(rawError))
  }
)
