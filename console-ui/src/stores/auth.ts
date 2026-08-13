import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { gatewayApi } from '@/api/gateway'
import { beginStoredSession, clearStoredSession, GatewayApiError, SESSION_UPDATED_EVENT } from '@/utils/request'
import type { LoginResult, PrincipalInfo } from '@/types/gateway'

const safeArray = (key: string): string[] => {
  try {
    const value = JSON.parse(localStorage.getItem(key) || '[]')
    return Array.isArray(value) ? value.map(String) : []
  } catch {
    return []
  }
}

const AUTH_STORAGE_KEYS = new Set([
  'console_token',
  'console_refresh_token',
  'console_principal',
  'console_user',
  'console_roles',
  'console_perms',
  'console_auth_mode'
])

const PRINCIPAL_STORAGE_KEY = 'console_principal'

interface StoredPrincipalSnapshot {
  subject: string
  roles: string[]
  permissions: string[]
}

function readPrincipalSnapshot(): StoredPrincipalSnapshot | null {
  try {
    const value = JSON.parse(localStorage.getItem(PRINCIPAL_STORAGE_KEY) || 'null') as Partial<StoredPrincipalSnapshot> | null
    if (!value || typeof value.subject !== 'string') return null
    return {
      subject: value.subject,
      roles: Array.isArray(value.roles) ? value.roles.map(String) : [],
      permissions: Array.isArray(value.permissions) ? value.permissions.map(String) : []
    }
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const principalSnapshot = readPrincipalSnapshot()
  const token = ref(localStorage.getItem('console_token') || '')
  const username = ref(principalSnapshot?.subject || localStorage.getItem('console_user') || '')
  const roles = ref<string[]>(principalSnapshot?.roles || safeArray('console_roles'))
  const permissions = ref<string[]>(principalSnapshot?.permissions || safeArray('console_perms'))
  const authMode = ref(localStorage.getItem('console_auth_mode') || 'stub')
  const restored = ref(false)
  let restorePromise: Promise<boolean> | null = null

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => roles.value.includes('admin') || permissions.value.includes('*'))

  function syncStoredSession() {
    token.value = localStorage.getItem('console_token') || ''
    const snapshot = readPrincipalSnapshot()
    username.value = snapshot?.subject || localStorage.getItem('console_user') || ''
    roles.value = snapshot?.roles || safeArray('console_roles')
    permissions.value = snapshot?.permissions || safeArray('console_perms')
    authMode.value = localStorage.getItem('console_auth_mode') || 'stub'
  }

  function persistPrincipal(principal: PrincipalInfo) {
    const subject = principal.subject || principal.username || principal.userId
    if (!subject) {
      throw new GatewayApiError('当前用户响应缺少主体标识', {
        errorCode: 'AUTHENTICATION_FAILED'
      })
    }
    username.value = subject
    roles.value = principal.roles || []
    permissions.value = principal.permissions || []
    localStorage.setItem(PRINCIPAL_STORAGE_KEY, JSON.stringify({
      subject: username.value,
      roles: roles.value,
      permissions: permissions.value
    } satisfies StoredPrincipalSnapshot))
  }

  function persistTokens(result: LoginResult) {
    beginStoredSession()
    token.value = result.token
    localStorage.setItem('console_token', result.token)
    localStorage.setItem('console_refresh_token', result.refreshToken)
    localStorage.setItem('console_expires_at', String(Date.now() + result.expiresInSeconds * 1000))
    localStorage.setItem(
      'console_refresh_expires_at',
      String(Date.now() + result.refreshExpiresInSeconds * 1000)
    )
  }

  async function fetchCapabilities() {
    try {
      const capabilities = await gatewayApi.authCapabilities()
      authMode.value = capabilities.authProvider || 'stub'
      localStorage.setItem('console_auth_mode', authMode.value)
      return capabilities
    } catch {
      return null
    }
  }

  async function login(user: string, pass: string) {
    const result = await gatewayApi.login(user, pass)
    persistTokens(result)
    persistPrincipal(result.principal)
    restored.value = true
  }

  async function fetchMe(): Promise<PrincipalInfo | null> {
    const requestIdentity = `${localStorage.getItem('console_token') || ''}\u0000${localStorage.getItem('console_refresh_token') || ''}\u0000${localStorage.getItem(PRINCIPAL_STORAGE_KEY) || ''}`
    const principal = await gatewayApi.whoami()
    const currentIdentity = `${localStorage.getItem('console_token') || ''}\u0000${localStorage.getItem('console_refresh_token') || ''}\u0000${localStorage.getItem(PRINCIPAL_STORAGE_KEY) || ''}`
    if (requestIdentity !== currentIdentity) return null
    persistPrincipal(principal)
    return principal
  }

  async function restoreSession(): Promise<boolean> {
    if (restored.value) return isLoggedIn.value
    if (restorePromise) return restorePromise

    restorePromise = (async () => {
      token.value = localStorage.getItem('console_token') || ''
      if (!token.value) {
        restored.value = true
        return false
      }
      const restoreIdentity = `${localStorage.getItem('console_token') || ''}\u0000${localStorage.getItem('console_refresh_token') || ''}\u0000${localStorage.getItem(PRINCIPAL_STORAGE_KEY) || ''}`
      try {
        await fetchMe()
        token.value = localStorage.getItem('console_token') || token.value
        return true
      } catch {
        const currentIdentity = `${localStorage.getItem('console_token') || ''}\u0000${localStorage.getItem('console_refresh_token') || ''}\u0000${localStorage.getItem(PRINCIPAL_STORAGE_KEY) || ''}`
        if (restoreIdentity === currentIdentity) {
          clearLocalSession()
          return false
        }
        syncStoredSession()
        return isLoggedIn.value
      } finally {
        restored.value = true
        restorePromise = null
      }
    })()

    return restorePromise
  }

  function clearLocalSession() {
    clearStoredSession()
    syncStoredSession()
    restored.value = true
    restorePromise = null
  }

  async function logout(): Promise<boolean> {
    const accessToken = localStorage.getItem('console_token') || ''
    clearLocalSession()
    if (!accessToken) return true
    try {
      await gatewayApi.logout(accessToken)
      return true
    } catch {
      return false
    }
  }

  window.addEventListener(SESSION_UPDATED_EVENT, syncStoredSession)

  window.addEventListener('storage', (event) => {
    if (event.storageArea && event.storageArea !== localStorage) return
    if (event.key !== null && !AUTH_STORAGE_KEYS.has(event.key)) return

    const wasLoggedIn = isLoggedIn.value
    syncStoredSession()
    restored.value = true
    restorePromise = null

    if (wasLoggedIn && !isLoggedIn.value && window.location.pathname !== '/login') {
      const redirect = encodeURIComponent(window.location.pathname + window.location.search)
      window.location.assign(`/login?redirect=${redirect}`)
    }
  })

  return {
    token,
    username,
    roles,
    permissions,
    authMode,
    restored,
    isLoggedIn,
    isAdmin,
    login,
    logout,
    fetchMe,
    fetchCapabilities,
    restoreSession
  }
})
