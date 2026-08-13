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

const seriesColors = ['#1769aa', '#18794e', '#b42318', '#9a5b13', '#6b4fa1', '#44566c']
const chartOption = computed(() => ({
  animationDuration: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 220,
  aria: { enabled: true, description: chartSummary.value },
  color: seriesColors,
  tooltip: { trigger: 'axis' },
  legend: { top: 4, left: 8, type: 'scroll' },
  grid: { left: 52, right: 24, top: 52, bottom: 42 },
  xAxis: {
    type: 'category',
    data: timeBuckets.value.map((time) => new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit' }).format(new Date(time))),
    axisLabel: { color: '#536171', hideOverlap: true },
    axisLine: { lineStyle: { color: '#c7d0da' } }
  },
  yAxis: {
    type: 'value',
    minInterval: 1,
    name: '次数',
    nameTextStyle: { color: '#748295' },
    axisLabel: { color: '#536171' },
    splitLine: { lineStyle: { color: '#e8ecf0' } }
  },
  series: resultCodes.value.map((code) => ({
    name: code,
    type: 'line',
    smooth: false,
    symbolSize: 7,
    showSymbol: timeBuckets.value.length < 48,
    data: timeBuckets.value.map((time) => Number(timeSeries.value.find((point) => Number(point.time) === time && point.resultCode === code)?.count || 0)),
    emphasis: { focus: 'series' }
  }))
}))

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

.readiness-alert,
.inline-error {
  margin-bottom: 16px;
}

.error-copy {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 2px;
}

.inline-error {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  color: var(--gateway-danger);
  background: #fff5f4;
  border: 1px solid #f6c7c3;
  border-radius: 6px;
}

.trend-section,
.capability-section {
  margin-top: 16px;
}

.chart-wrap {
  position: relative;
  width: 100%;
  height: 360px;
  padding: 8px 8px 0;
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
