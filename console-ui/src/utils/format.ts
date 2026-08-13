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
    return `${error.message}${correlation}`
  }
  if (error instanceof Error) return error.message
  return '请求失败，请稍后重试'
}

export function isSuccessResult(resultCode?: string): boolean {
  return ['SUCCESS', 'REQUEST_ACCEPTED', 'STARTED', 'COMPLETED', 'PUBLISHED', 'APPROVED'].includes(resultCode || '')
}

