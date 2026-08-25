<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h2 class="page-title">治理总览</h2>
        <p class="page-subtitle">聚合当前发布状态、治理待办和运行异常。</p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadOverview">刷新</el-button>
        <el-button v-if="auth.isAdmin" type="primary" :icon="Upload" @click="router.push('/capabilities?action=import')">
          导入能力
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="partialErrors.length"
      class="partial-alert"
      type="warning"
      show-icon
      :closable="false"
      title="部分数据暂不可用，已展示其余可用信息。"
    >
      <template #default>不可用分区：{{ partialErrorLabels }}</template>
    </el-alert>

    <section class="stat-grid" aria-label="关键状态">
      <article class="stat-card">
        <div class="stat-label"><el-icon><CircleCheck /></el-icon> 网关就绪状态</div>
        <div class="stat-value" :class="readiness?.status === 'UP' ? 'success-text' : 'danger-text'">
          {{ readiness?.status === 'UP' ? '就绪' : readiness ? '未就绪' : '-' }}
        </div>
        <div class="stat-meta">{{ readinessSummary }}</div>
      </article>
      <article class="stat-card">
        <div class="stat-label"><el-icon><Files /></el-icon> 生产活动快照</div>
        <div class="stat-value data-number">{{ activeSnapshot ? `v${activeSnapshot.snapshotVersion}` : '-' }}</div>
        <div class="stat-meta">{{ activeSnapshot ? `${activeSnapshot.capabilityCount} 项能力` : '尚未发布活动快照' }}</div>
      </article>
      <article class="stat-card">
        <div class="stat-label"><el-icon><List /></el-icon> 已治理能力</div>
        <div class="stat-value data-number">{{ formatNumber(capabilities.length) }}</div>
        <div class="stat-meta">{{ publishedCount }} 项已发布，{{ pendingCount }} 项待处理</div>
      </article>
      <article class="stat-card">
        <div class="stat-label"><el-icon><DataLine /></el-icon> 24 小时成功事件占比</div>
        <div class="stat-value data-number" :class="successRateClass">{{ successRateLabel }}</div>
        <div class="stat-meta">{{ formatNumber(totalCalls) }} 条能力审计事件，{{ formatNumber(failureCalls) }} 条失败事件</div>
      </article>
    </section>

    <div class="section-grid">
      <section class="surface" aria-labelledby="work-queue-title">
        <header class="surface-header">
          <div>
            <h3 id="work-queue-title" class="surface-title">治理待办</h3>
            <span class="muted">按生命周期给出当前可执行的下一步</span>
          </div>
          <el-button text type="primary" @click="router.push('/capabilities')">查看全部</el-button>
        </header>
        <div v-if="workQueue.length" class="queue-list">
          <button
            v-for="item in workQueue"
            :key="item.lifecycle"
            class="queue-item"
            type="button"
            @click="router.push({ path: '/capabilities', query: { lifecycle: item.lifecycle } })"
          >
            <span class="queue-status" :class="`queue-status--${item.tone}`"><el-icon><component :is="item.icon" /></el-icon></span>
            <span class="queue-copy">
              <strong>{{ item.title }}</strong>
              <small>{{ item.description }}</small>
            </span>
            <span class="queue-count data-number">{{ item.count }}</span>
            <el-icon><ArrowRight /></el-icon>
          </button>
        </div>
        <div v-else class="empty-state">
          <div><strong>当前没有治理待办</strong><span>所有能力均已进入稳定状态。</span></div>
        </div>
      </section>

      <section class="surface" aria-labelledby="readiness-title">
        <header class="surface-header">
          <h3 id="readiness-title" class="surface-title">Readiness 检查</h3>
          <el-tag :type="readiness?.status === 'UP' ? 'success' : 'danger'" effect="plain">
            {{ readiness?.status || 'UNKNOWN' }}
          </el-tag>
        </header>
        <div v-if="readiness" class="check-list">
          <div v-for="(status, name) in readiness.checks" :key="name" class="check-row">
            <span>{{ readinessLabels[name] || name }}</span>
            <span :class="status === 'UP' ? 'success-text' : 'danger-text'">
              <el-icon><CircleCheck v-if="status === 'UP'" /><CircleClose v-else /></el-icon>
              {{ status }}
            </span>
          </div>
        </div>
        <div v-else class="empty-state">
          <div><strong>无法读取就绪状态</strong><span>请检查网关进程和网络连接。</span></div>
        </div>
      </section>
    </div>

    <section v-if="canReadAudit" class="surface recent-section" aria-labelledby="recent-title">
      <header class="surface-header">
        <div>
          <h3 id="recent-title" class="surface-title">近期异常</h3>
          <span class="muted">最近审计事件中的失败和拒绝结果</span>
        </div>
        <el-button text type="primary" @click="router.push('/audit')">进入审计追踪</el-button>
      </header>
      <div class="table-wrap">
        <el-table v-if="recentFailures.length" :data="recentFailures" style="min-width: 760px" @row-click="openAudit">
          <el-table-column prop="timestamp" label="时间" width="178">
            <template #default="{ row }">{{ formatDateTime(row.timestamp) }}</template>
          </el-table-column>
          <el-table-column prop="eventType" label="阶段" width="180" />
          <el-table-column prop="capabilityId" label="能力" min-width="200" show-overflow-tooltip />
          <el-table-column prop="resultCode" label="结果" width="180">
            <template #default="{ row }"><el-tag type="danger" effect="plain">{{ row.resultCode || 'FAILED' }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="requestId" label="Request ID" min-width="190" show-overflow-tooltip />
          <el-table-column label="操作" width="88"><template #default="{ row }"><el-button text type="primary" @click.stop="openAudit(row)">追踪</el-button></template></el-table-column>
        </el-table>
        <div v-else class="empty-state">
          <div><strong>近期未发现异常</strong><span>当前筛选窗口内没有失败审计事件。</span></div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  CircleCheck,
  CircleClose,
  DataLine,
  Files,
  List,
  Refresh,
  Select,
  Upload,
  Warning
} from '@element-plus/icons-vue'
import { gatewayApi } from '@/api/gateway'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, formatNumber, isSuccessResult } from '@/utils/format'
import type {
  AuditEvent,
  CapabilityLifecycle,
  CapabilityStat,
  CapabilitySummary,
  HealthStatus,
  SnapshotSummary
} from '@/types/gateway'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const partialErrors = ref<string[]>([])
const capabilities = ref<CapabilitySummary[]>([])
const snapshots = ref<SnapshotSummary[]>([])
const readiness = ref<HealthStatus>()
const stats = ref<CapabilityStat[]>([])
const recentEvents = ref<AuditEvent[]>([])

const sectionLabels: Record<string, string> = {
  capabilities: '能力目录',
  snapshots: '发布快照',
  readiness: 'Readiness',
  stats: '能力审计统计',
  audits: '近期审计'
}

const canReadAudit = computed(() => auth.isAdmin)
const canReadMonitor = computed(() => auth.isAdmin)
const partialErrorLabels = computed(() => partialErrors.value.map((key) => sectionLabels[key] || key).join('、'))
const activeSnapshot = computed(() => snapshots.value.find((item) => item.status === 'ACTIVE'))
const publishedCount = computed(() => capabilities.value.filter((item) => item.lifecycle === 'PUBLISHED').length)
const pendingCount = computed(() => capabilities.value.filter((item) => ['DRAFT', 'VALIDATED', 'APPROVED', 'REJECTED'].includes(item.lifecycle)).length)
const totalCalls = computed(() => stats.value.reduce((sum, row) => sum + Number(row.total_calls || 0), 0))
const successCalls = computed(() => stats.value.reduce((sum, row) => sum + Number(row.success_count || 0), 0))
const failureCalls = computed(() => stats.value.reduce((sum, row) => sum + Number(row.failure_count || 0), 0))
const successRate = computed(() => totalCalls.value ? successCalls.value / totalCalls.value * 100 : undefined)
const successRateLabel = computed(() => successRate.value === undefined ? '-' : `${successRate.value.toFixed(1)}%`)
const successRateClass = computed(() => successRate.value === undefined ? '' : successRate.value >= 99 ? 'success-text' : successRate.value >= 95 ? 'warning-text' : 'danger-text')
const readinessSummary = computed(() => {
  if (!readiness.value) return '无法获取网关健康信息'
  const checks = Object.values(readiness.value.checks)
  return `${checks.filter((status) => status === 'UP').length}/${checks.length} 项检查通过`
})
const recentFailures = computed(() => recentEvents.value
  .filter((event) => !!event.resultCode && !isSuccessResult(event.resultCode))
  .slice(0, 6))

const readinessLabels: Record<string, string> = {
  database: 'PostgreSQL',
  activeSnapshot: '活动快照',
  requiredSecrets: '必需密钥',
  adapterInitialization: '适配器初始化'
}

const queueDefinitions: Array<{
  lifecycle: CapabilityLifecycle
  title: string
  description: string
  tone: 'info' | 'warning' | 'danger'
  icon: typeof Select
}> = [
  { lifecycle: 'DRAFT', title: '待校验', description: '运行 Manifest 结构、安全和兼容性检查', tone: 'info', icon: Select },
  { lifecycle: 'VALIDATED', title: '待审批', description: '复核风险、权限和执行摘要', tone: 'warning', icon: Warning },
  { lifecycle: 'APPROVED', title: '待发布', description: '将已审批能力纳入新快照', tone: 'warning', icon: Upload },
  { lifecycle: 'REJECTED', title: '校验未通过', description: '查看错误并提交修正后的新版本', tone: 'danger', icon: CircleClose }
]

const workQueue = computed(() => queueDefinitions
  .map((item) => ({ ...item, count: capabilities.value.filter((capability) => capability.lifecycle === item.lifecycle).length }))
  .filter((item) => item.count > 0))

onMounted(loadOverview)

async function loadOverview() {
  loading.value = true
  partialErrors.value = []
  const now = Date.now()
  const tasks = [
    gatewayApi.capabilities(),
    gatewayApi.snapshots(),
    gatewayApi.readiness(),
    canReadMonitor.value ? gatewayApi.capabilityStats(now - 24 * 60 * 60 * 1000, now) : Promise.resolve([]),
    canReadAudit.value ? gatewayApi.audits({ page: 1, size: 30, from: now - 24 * 60 * 60 * 1000, to: now }) : Promise.resolve({ items: [], total: 0 })
  ] as const

  const results = await Promise.allSettled(tasks)
  if (results[0].status === 'fulfilled') capabilities.value = results[0].value
  else {
    capabilities.value = []
    partialErrors.value.push('capabilities')
  }
  if (results[1].status === 'fulfilled') snapshots.value = results[1].value
  else {
    snapshots.value = []
    partialErrors.value.push('snapshots')
  }
  if (results[2].status === 'fulfilled') readiness.value = results[2].value
  else {
    readiness.value = undefined
    partialErrors.value.push('readiness')
  }
  if (results[3].status === 'fulfilled') stats.value = results[3].value
  else {
    stats.value = []
    partialErrors.value.push('stats')
  }
  if (results[4].status === 'fulfilled') recentEvents.value = results[4].value.items
  else {
    recentEvents.value = []
    partialErrors.value.push('audits')
  }
  loading.value = false
}

function openAudit(row: AuditEvent) {
  router.push({ path: '/audit', query: row.requestId ? { requestId: row.requestId } : undefined })
}
</script>

<style scoped>
.partial-alert {
  margin-bottom: 16px;
}

.queue-list {
  padding: 4px 0;
}

.queue-item {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto 20px;
  width: 100%;
  min-height: 68px;
  padding: 10px 16px;
  align-items: center;
  gap: 12px;
  color: var(--gateway-text);
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--gateway-border);
  cursor: pointer;
  text-align: left;
}

.queue-item:last-child {
  border-bottom: 0;
}

.queue-item:hover {
  background: var(--gateway-surface-subtle);
}

.queue-status {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 6px;
}

.queue-status--info {
  color: var(--gateway-primary);
  background: var(--gateway-primary-soft);
}

.queue-status--warning {
  color: var(--gateway-warning);
  background: var(--gateway-warning-soft);
}

.queue-status--danger {
  color: var(--gateway-danger);
  background: var(--gateway-danger-soft);
}

.queue-copy strong,
.queue-copy small {
  display: block;
}

.queue-copy small {
  color: var(--gateway-text-muted);
}

.queue-count {
  min-width: 32px;
  font-size: 20px;
  font-weight: 700;
  text-align: right;
}

.check-list {
  padding: 4px 16px;
}

.check-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 52px;
  border-bottom: 1px solid var(--gateway-border);
}

.check-row:last-child {
  border-bottom: 0;
}

.check-row > span:last-child {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 650;
}

.recent-section {
  margin-top: 16px;
}

:deep(.el-table__row) {
  cursor: pointer;
}

@media (max-width: 600px) {
  .queue-item {
    grid-template-columns: 36px minmax(0, 1fr) auto;
    padding-inline: 12px;
  }

  .queue-item > .el-icon:last-child {
    display: none;
  }
}
</style>
