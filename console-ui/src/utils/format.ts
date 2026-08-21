import { GatewayApiError } from '@/utils/request'

export function formatDateTime(value?: string | number | Date): string {
  if (value === undefined || value === null || value === '') return '-'
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  }).format(date)
}

export function formatNumber(value: number): string {
  return new Intl.NumberFormat('zh-CN').format(Number.isFinite(value) ? value : 0)
}

export function formatDuration(value?: number): string {
  if (value === undefined || value === null || !Number.isFinite(value)) return '-'
  if (value < 1000) return `${Math.round(value)} ms`
  return `${(value / 1000).toFixed(value >= 10000 ? 1 : 2)} s`
}

export function formatBytes(value?: number): string {
  if (value === undefined || !Number.isFinite(value)) return '-'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

export function apiErrorMessage(error: unknown): string {
  if (error instanceof GatewayApiError) {
    const correlation = error.traceId ? `（Trace ID: ${error.traceId}）` : ''
    return `${localizedErrorMessage(error.errorCode, error.message, error.status)}${correlation}`
  }
  if (error instanceof Error) return localizedErrorMessage(undefined, error.message)
  return '请求失败，请稍后重试'
}

export function localizedErrorMessage(errorCode?: string, message?: string, status?: number): string {
  const codeMessages: Record<string, string> = {
    AUTHENTICATION_FAILED: '登录状态已失效，请重新登录',
    SESSION_SUPERSEDED: '当前会话已被其他操作更新，请重试',
    PERMISSION_DENIED: '没有执行此操作的权限',
    REQUEST_FAILED: '请求失败，请稍后重试',
    NO_CAPABILITY_MATCH: '没有匹配到可用能力',
    CAPABILITY_UNAVAILABLE: '能力当前不可用',
    ARGUMENT_VALIDATION_FAILED: '请求参数校验失败',
    INVALID_MODEL_OUTPUT: '模型返回结果无效',
    CONFIRMATION_REQUIRED: '该操作需要确认后才能继续',
    STALE_SNAPSHOT: '目录快照已过期，请刷新后重试',
    HIGH_RISK_WRITE_BLOCKED: '高风险写操作已被拦截',
    PREPARE_FAILED: '操作准备失败'
  }
  if (errorCode && codeMessages[errorCode]) return codeMessages[errorCode]

  const normalized = String(message || '').trim()
  if (!normalized) {
    if (status === 401) return codeMessages.AUTHENTICATION_FAILED
    if (status === 403) return codeMessages.PERMISSION_DENIED
    if (status === 404) return '请求的资源不存在'
    if (status === 408 || status === 504) return '请求超时，请稍后重试'
    if (status !== undefined && status >= 500) return '网关服务暂时不可用，请稍后重试'
    return codeMessages.REQUEST_FAILED
  }

  const messageMappings: Array<[RegExp, string]> = [
    [/network error/i, '网络连接失败，请检查网络或网关服务'],
    [/timeout|timed out/i, '请求超时，请稍后重试'],
    [/unauthorized|authentication failed|not authenticated/i, codeMessages.AUTHENTICATION_FAILED],
    [/forbidden|permission denied|access denied/i, codeMessages.PERMISSION_DENIED],
    [/not found|resource .* missing|manifest not found/i, '请求的资源不存在'],
    [/no active snapshot found/i, '没有找到活动目录快照'],
    [/no approved manifests to publish/i, '没有可发布的已审批能力'],
    [/validation failed/i, '校验失败'],
    [/failed to generate content sha-?256 digest/i, '生成内容摘要失败'],
    [/request failed/i, codeMessages.REQUEST_FAILED]
  ]
  const matched = messageMappings.find(([pattern]) => pattern.test(normalized))
  if (matched) return matched[1]

  return normalized
}

export function isSuccessResult(resultCode?: string): boolean {
  return ['SUCCESS', 'REQUEST_ACCEPTED', 'STARTED', 'COMPLETED', 'PUBLISHED', 'APPROVED'].includes(resultCode || '')
}
