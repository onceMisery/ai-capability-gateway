<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h2 class="page-title">运行监控</h2>
        <p class="page-subtitle">查看网关就绪状态、审计事件趋势和能力级审计表现。</p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadStats">刷新</el-button>
      </div>
    </header>

    <section class="monitor-toolbar" aria-label="时间范围">
      <el-segmented v-model="rangePreset" :options="rangeOptions" @change="applyPreset" />
      <el-date-picker
        v-model="timeRange"
        type="datetimerange"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        :clearable="false"
        @change="handleCustomRange"
      />
    </section>

    <el-alert
      v-if="readiness && readiness.status !== 'UP'"
      class="readiness-alert"
      type="error"
      show-icon
      :closable="false"
      title="网关当前未通过 Readiness 检查，不应接收新流量。"
    >
      <template #default>{{ failedChecksLabel }}</template>
    </el-alert>

    <div v-if="sectionErrors.length" class="inline-error" role="alert">
      <el-icon><Warning /></el-icon>
      <div class="error-copy">
        <strong>部分监控数据不可用</strong>
        <span v-for="error in sectionErrors" :key="error.section">{{ error.section }}：{{ error.message }}</span>
      </div>
      <el-button text type="primary" @click="loadStats">重试</el-button>
    </div>

    <section class="stat-grid" aria-label="运行指标">
      <article class="stat-card">
        <div class="stat-label"><el-icon><Connection /></el-icon> 审计事件量</div>
        <div class="stat-value data-number">{{ formatNumber(totalCalls) }}</div>
        <div class="stat-meta">所选时间范围</div>
      </article>
      <article class="stat-card">
        <div class="stat-label"><el-icon><CircleCheck /></el-icon> 成功事件占比</div>
        <div class="stat-value data-number" :class="successRateClass">{{ successRateLabel }}</div>
        <div class="stat-meta">{{ formatNumber(successCalls) }} 条成功事件</div>
      </article>
      <article class="stat-card">
        <div class="stat-label"><el-icon><CircleClose /></el-icon> 失败事件</div>
        <div class="stat-value data-number" :class="failureCalls ? 'danger-text' : 'success-text'">{{ formatNumber(failureCalls) }}</div>
        <div class="stat-meta">涉及 {{ capabilityStats.length }} 项能力</div>
      </article>
      <article class="stat-card">
        <div class="stat-label"><el-icon><Timer /></el-icon> 事件平均耗时</div>
        <div class="stat-value data-number">{{ formatDuration(weightedAverageDuration) }}</div>
        <div class="stat-meta">按能力审计事件量加权</div>
      </article>
    </section>

    <section class="surface trend-section" aria-labelledby="trend-title">
      <header class="surface-header">
        <div>
          <h3 id="trend-title" class="surface-title">审计结果趋势</h3>
          <span class="muted">按小时聚合，颜色与图例共同标识结果类型</span>
        </div>
      </header>
      <div v-if="timeSeries.length" class="chart-wrap">
        <VChart class="trend-chart" :option="chartOption" autoresize aria-label="审计结果按小时趋势图" />
        <p class="sr-summary">{{ chartSummary }}</p>
      </div>
      <div v-else-if="!loading" class="empty-state"><div><strong>暂无趋势数据</strong><span>所选时间范围内没有审计事件。</span></div></div>
      <el-skeleton v-else :rows="5" animated class="chart-skeleton" />
    </section>

    <section class="surface capability-section" aria-labelledby="capability-stats-title">
      <header class="surface-header">
        <div>
          <h3 id="capability-stats-title" class="surface-title">能力审计事件排行</h3>
          <span class="muted">点击能力可进入对应审计记录</span>
        </div>
      </header>
      <div class="table-wrap">
        <el-table v-if="capabilityStats.length" :data="capabilityStats" stripe style="min-width: 780px" @row-click="openCapabilityAudit">
          <el-table-column label="能力" min-width="260"><template #default="{ row }"><button class="capability-link mono" type="button" @click.stop="openCapabilityAudit(row)">{{ row.capability_id }}</button></template></el-table-column>
          <el-table-column label="事件量" width="110" sortable prop="total_calls"><template #default="{ row }"><span class="data-number">{{ formatNumber(Number(row.total_calls)) }}</span></template></el-table-column>
          <el-table-column label="成功事件" width="120" prop="success_count"><template #default="{ row }"><span class="success-text data-number">{{ formatNumber(Number(row.success_count)) }}</span></template></el-table-column>
          <el-table-column label="失败事件" width="120" sortable prop="failure_count"><template #default="{ row }"><span :class="Number(row.failure_count) ? 'danger-text' : 'muted'" class="data-number">{{ formatNumber(Number(row.failure_count)) }}</span></template></el-table-column>
          <el-table-column label="成功事件占比" width="150"><template #default="{ row }"><span class="data-number">{{ rowSuccessRate(row) }}</span></template></el-table-column>
          <el-table-column label="事件平均耗时" width="150" sortable prop="avg_duration_ms"><template #default="{ row }"><span class="data-number">{{ formatDuration(Number(row.avg_duration_ms)) }}</span></template></el-table-column>
          <el-table-column label="" width="52"><template #default><el-icon><ArrowRight /></el-icon></template></el-table-column>
        </el-table>
        <div v-else-if="!loading" class="empty-state"><div><strong>暂无能力审计事件</strong><span>所选时间范围内没有能力级审计数据。</span></div></div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { AriaComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { ArrowRight, CircleCheck, CircleClose, Connection, Refresh, Timer, Warning } from '@element-plus/icons-vue'
import { gatewayApi } from '@/api/gateway'
import { apiErrorMessage, formatDuration, formatNumber } from '@/utils/format'
import type { CapabilityStat, HealthStatus, TimeSeriesPoint } from '@/types/gateway'

use([CanvasRenderer, LineChart, AriaComponent, GridComponent, LegendComponent, TooltipComponent])

const router = useRouter()
const loading = ref(false)
const sectionErrors = ref<Array<{ section: string; message: string }>>([])
const capabilityStats = ref<CapabilityStat[]>([])
const timeSeries = ref<TimeSeriesPoint[]>([])
const readiness = ref<HealthStatus>()
const rangePreset = ref('24h')
const timeRange = ref<[Date, Date]>([new Date(Date.now() - 24 * 60 * 60 * 1000), new Date()])
const rangeOptions = [
  { label: '1 小时', value: '1h' },
  { label: '24 小时', value: '24h' },
  { label: '7 天', value: '7d' }
]

const totalCalls = computed(() => capabilityStats.value.reduce((sum, row) => sum + Number(row.total_calls || 0), 0))
const successCalls = computed(() => capabilityStats.value.reduce((sum, row) => sum + Number(row.success_count || 0), 0))
const failureCalls = computed(() => capabilityStats.value.reduce((sum, row) => sum + Number(row.failure_count || 0), 0))
const successRate = computed(() => totalCalls.value ? successCalls.value / totalCalls.value * 100 : undefined)
const successRateLabel = computed(() => successRate.value === undefined ? '-' : `${successRate.value.toFixed(1)}%`)
const successRateClass = computed(() => successRate.value === undefined ? '' : successRate.value >= 99 ? 'success-text' : successRate.value >= 95 ? 'warning-text' : 'danger-text')
const weightedAverageDuration = computed(() => {
  if (!totalCalls.value) return undefined
  const sum = capabilityStats.value.reduce((acc, row) => acc + Number(row.avg_duration_ms || 0) * Number(row.total_calls || 0), 0)
  return sum / totalCalls.value
})
const failedChecksLabel = computed(() => Object.entries(readiness.value?.checks || {}).filter(([, status]) => status !== 'UP').map(([name]) => name).join('、') || '未知检查项')
const resultCodes = computed(() => [...new Set(timeSeries.value.map((point) => point.resultCode))].sort())
const timeBuckets = computed(() => [...new Set(timeSeries.value.map((point) => Number(point.time)))].sort((a, b) => a - b))
const chartSummary = computed(() => `所选范围共 ${formatNumber(totalCalls.value)} 条能力审计事件，成功事件 ${formatNumber(successCalls.value)} 条，失败事件 ${formatNumber(failureCalls.value)} 条。`)

const resultCodeLabels: Record<string, string> = {
  SUCCESS: '成功',
  COMPLETED: '已完成',
  AUTHENTICATION_FAILED: '认证失败',
  PERMISSION_DENIED: '权限不足',
  ARGUMENT_VALIDATION_FAILED: '参数校验失败',
  CAPABILITY_UNAVAILABLE: '能力不可用',
  PROVIDER_TIMEOUT: 'Provider 超时',
  PROVIDER_REJECTED: 'Provider 拒绝',
  PROTOCOL_ERROR: '协议错误',
  RATE_LIMITED: '触发限流',
  EXECUTION_UNKNOWN: '执行结果未知'
}

function readToken(name: string, fallback: string) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback
}

function resultCodeLabel(code: string) {
  return resultCodeLabels[code] || code
}

function resultCodeColor(code: string) {
  if (['SUCCESS', 'COMPLETED'].includes(code)) return readToken('--gateway-success', '#15803d')
  if (['AUTHENTICATION_FAILED', 'PERMISSION_DENIED'].includes(code)) return readToken('--gateway-primary', '#2563eb')
  if (['PROVIDER_TIMEOUT', 'RATE_LIMITED'].includes(code)) return readToken('--gateway-warning', '#b45309')
  if (['PROTOCOL_ERROR', 'PROVIDER_REJECTED', 'EXECUTION_UNKNOWN'].includes(code)) return readToken('--gateway-danger', '#dc2626')
  return readToken('--gateway-info', '#475569')
}

const chartOption = computed(() => {
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const axisText = readToken('--gateway-text-muted', '#64748b')
  const border = readToken('--gateway-border', '#e5e5e5')
  const primary = readToken('--gateway-primary', '#2563eb')
  return {
    animationDuration: reducedMotion ? 0 : 280,
    aria: { enabled: true, description: chartSummary.value },
    color: resultCodes.value.map(resultCodeColor),
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'line',
        lineStyle: { color: primary, width: 1, opacity: 0.45 }
      },
      backgroundColor: '#111827',
      borderColor: 'rgba(148, 163, 184, 0.28)',
      borderWidth: 1,
      padding: [10, 12],
      textStyle: { color: '#f8fafc', fontSize: 12 },
      formatter: (params: Array<{ seriesName: string; value: number; axisValue: string }>) => {
        const title = params[0]?.axisValue || ''
        const rows = params
          .filter((item) => Number(item.value) > 0)
          .map((item) => `<div style="display:flex;justify-content:space-between;gap:28px;margin-top:6px"><span>${resultCodeLabel(item.seriesName)}</span><strong>${formatNumber(Number(item.value))}</strong></div>`)
        return `<div style="min-width:150px"><strong>${title}</strong>${rows.join('') || '<div style="margin-top:6px;color:#94a3b8">暂无事件</div>'}</div>`
      }
    },
    legend: {
      top: 12,
      left: 14,
      right: 14,
      type: 'scroll',
      icon: 'roundRect',
      itemWidth: 18,
      itemHeight: 4,
      itemGap: 18,
      textStyle: { color: axisText, fontSize: 12 },
      formatter: (name: string) => resultCodeLabel(name)
    },
    grid: { left: 54, right: 22, top: 58, bottom: 44, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: timeBuckets.value.map((time) => new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit' }).format(new Date(time))),
      axisLabel: { color: axisText, hideOverlap: true, margin: 12 },
      axisLine: { lineStyle: { color: border } },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      name: '事件数',
      nameTextStyle: { color: axisText, padding: [0, 0, 8, 0] },
      axisLabel: { color: axisText, margin: 12 },
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: border, type: 'dashed', opacity: 0.75 } }
    },
    series: resultCodes.value.map((code) => ({
      name: code,
      type: 'line',
      smooth: 0.28,
      connectNulls: true,
      symbol: 'circle',
      symbolSize: timeBuckets.value.length < 48 ? 6 : 0,
      showSymbol: timeBuckets.value.length < 48,
      lineStyle: { width: 2.5, color: resultCodeColor(code) },
      itemStyle: { color: resultCodeColor(code), borderColor: '#ffffff', borderWidth: 2 },
      areaStyle: { color: resultCodeColor(code), opacity: 0.08 },
      emphasis: {
        focus: 'series',
        lineStyle: { width: 3 },
        itemStyle: { borderWidth: 3 }
      },
      data: timeBuckets.value.map((time) => Number(timeSeries.value.find((point) => Number(point.time) === time && point.resultCode === code)?.count || 0))
    }))
  }
})

onMounted(loadStats)

function applyPreset() {
  const duration = rangePreset.value === '1h' ? 60 * 60 * 1000 : rangePreset.value === '7d' ? 7 * 24 * 60 * 60 * 1000 : 24 * 60 * 60 * 1000
  const end = new Date()
  timeRange.value = [new Date(end.getTime() - duration), end]
  loadStats()
}

function handleCustomRange() {
  rangePreset.value = ''
  loadStats()
}

async function loadStats() {
  if (!timeRange.value?.[0] || !timeRange.value?.[1]) return
  loading.value = true
  sectionErrors.value = []
  const from = timeRange.value[0].getTime()
  const to = timeRange.value[1].getTime()
  const results = await Promise.allSettled([
    gatewayApi.capabilityStats(from, to),
    gatewayApi.timeSeries(from, to),
    gatewayApi.readiness()
  ])
  if (results[0].status === 'fulfilled') capabilityStats.value = results[0].value
  else {
    capabilityStats.value = []
    sectionErrors.value.push({ section: '能力审计统计', message: apiErrorMessage(results[0].reason) })
  }
  if (results[1].status === 'fulfilled') timeSeries.value = results[1].value
  else {
    timeSeries.value = []
    sectionErrors.value.push({ section: '审计趋势', message: apiErrorMessage(results[1].reason) })
  }
  if (results[2].status === 'fulfilled') readiness.value = results[2].value
  else {
    readiness.value = undefined
    sectionErrors.value.push({ section: 'Readiness', message: apiErrorMessage(results[2].reason) })
  }
  loading.value = false
}

function rowSuccessRate(row: CapabilityStat) {
  const total = Number(row.total_calls || 0)
  return total ? `${(Number(row.success_count || 0) / total * 100).toFixed(1)}%` : '-'
}

function openCapabilityAudit(row: CapabilityStat) {
  router.push({ path: '/audit', query: { capabilityId: row.capability_id } })
}
</script>

<style scoped>
.monitor-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.readiness-alert {
  margin-bottom: 16px;
}

.error-copy {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 2px;
}

.trend-section,
.capability-section {
  margin-top: 16px;
}

.chart-wrap {
  position: relative;
  width: 100%;
  height: 376px;
  padding: 8px 14px 6px;
  background: var(--gateway-surface-subtle);
  border-top: 1px solid var(--gateway-border);
}

.trend-chart {
  width: 100%;
  height: 100%;
}

.chart-skeleton {
  min-height: 300px;
  padding: 32px;
}

.sr-summary {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.capability-link {
  display: inline-flex;
  align-items: center;
  min-width: 44px;
  min-height: 44px;
  padding: 0;
  color: var(--gateway-primary);
  background: transparent;
  border: 0;
  cursor: pointer;
  text-align: left;
}

.capability-link:hover {
  text-decoration: underline;
}

:deep(.el-table__row) {
  cursor: pointer;
}

@media (max-width: 700px) {
  .monitor-toolbar > * {
    width: 100%;
  }

  .chart-wrap {
    height: 300px;
    padding-inline: 0;
  }
}
</style>
