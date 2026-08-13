<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h2 class="page-title">审计追踪</h2>
        <p class="page-subtitle">按请求、能力和结果定位治理与执行链路中的关键事件。</p>
      </div>
      <div class="page-actions"><el-button :icon="Refresh" :loading="loading" @click="search">刷新</el-button></div>
    </header>

    <section class="surface audit-surface">
      <div class="surface-body">
        <div class="filter-grid" role="search" aria-label="审计筛选">
          <div class="filter-field filter-field--wide"><label class="field-label">时间范围</label><el-date-picker v-model="filters.timeRange" type="datetimerange" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" :clearable="false" style="width: 100%" /></div>
          <div class="filter-field"><label class="field-label" for="audit-request-id">Request ID</label><el-input id="audit-request-id" v-model.trim="filters.requestId" clearable placeholder="精确关联请求" /></div>
          <div class="filter-field"><label class="field-label" for="audit-capability-id">能力 ID</label><el-input id="audit-capability-id" v-model.trim="filters.capabilityId" clearable placeholder="能力标识" /></div>
          <div class="filter-field"><label class="field-label" for="audit-event-type">事件类型</label><el-select id="audit-event-type" v-model="filters.eventType" filterable allow-create clearable placeholder="全部事件" style="width: 100%"><el-option v-for="item in eventTypes" :key="item" :label="item" :value="item" /></el-select></div>
          <div class="filter-field"><label class="field-label" for="audit-result-code">结果码</label><el-select id="audit-result-code" v-model="filters.resultCode" filterable allow-create clearable placeholder="全部结果" style="width: 100%"><el-option v-for="item in resultCodes" :key="item" :label="item" :value="item" /></el-select></div>
          <div class="filter-actions"><el-button type="primary" :icon="Search" :loading="loading" @click="search">查询</el-button><el-button text @click="resetFilters">重置</el-button></div>
        </div>

        <div v-if="errorMsg" class="inline-error" role="alert"><el-icon><Warning /></el-icon>{{ errorMsg }}<el-button text type="primary" @click="search">重试</el-button></div>

        <div class="table-wrap">
          <el-table v-if="auditEvents.length" :data="auditEvents" v-loading="loading" stripe style="min-width: 1080px" @row-click="openDetail">
            <el-table-column label="时间" width="180"><template #default="{ row }">{{ formatDateTime(row.timestamp) }}</template></el-table-column>
            <el-table-column prop="eventType" label="事件类型" width="190" show-overflow-tooltip />
            <el-table-column label="结果" width="185"><template #default="{ row }"><el-tag :type="resultType(row.resultCode)" effect="plain">{{ row.resultCode || '-' }}</el-tag></template></el-table-column>
            <el-table-column prop="capabilityId" label="能力" min-width="220" show-overflow-tooltip><template #default="{ row }"><span class="mono">{{ row.capabilityId || '-' }}</span></template></el-table-column>
            <el-table-column prop="capabilityVersion" label="版本" width="90" />
            <el-table-column prop="durationMs" label="耗时" width="110"><template #default="{ row }"><span class="data-number">{{ formatDuration(row.durationMs) }}</span></template></el-table-column>
            <el-table-column prop="requestId" label="Request ID" min-width="210" show-overflow-tooltip><template #default="{ row }"><span class="mono">{{ row.requestId || '-' }}</span></template></el-table-column>
            <el-table-column label="操作" width="82"><template #default="{ row }"><el-button text type="primary" @click.stop="openDetail(row)">查看</el-button></template></el-table-column>
          </el-table>
          <div v-else-if="!loading" class="empty-state"><div><strong>没有匹配的审计事件</strong><span>调整时间范围或筛选条件后重试。</span></div></div>
        </div>

        <div class="pagination-row">
          <span class="muted">共 {{ total }} 条事件</span>
          <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[20, 50, 100]" layout="sizes, prev, pager, next" @current-change="loadData" @size-change="handlePageSize" />
        </div>
      </div>
    </section>

    <el-drawer v-model="detailOpen" title="审计事件详情" size="min(680px, 100vw)" destroy-on-close>
      <template v-if="selectedEvent">
        <div class="detail-heading">
          <div><span class="eyebrow">{{ selectedEvent.eventType }}</span><h3>{{ selectedEvent.resultCode || '无结果码' }}</h3><span>{{ formatDateTime(selectedEvent.timestamp) }}</span></div>
          <el-tag :type="resultType(selectedEvent.resultCode)" effect="plain">{{ selectedEvent.resultCode || '-' }}</el-tag>
        </div>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="Event ID"><span class="mono break-anywhere">{{ selectedEvent.eventId }}</span></el-descriptions-item>
          <el-descriptions-item label="Request ID"><span class="mono break-anywhere">{{ selectedEvent.requestId || '-' }}</span></el-descriptions-item>
          <el-descriptions-item label="Operation ID"><span class="mono break-anywhere">{{ selectedEvent.operationId || '-' }}</span></el-descriptions-item>
          <el-descriptions-item label="能力">{{ selectedEvent.capabilityId || '-' }}<span v-if="selectedEvent.capabilityVersion" class="muted"> · v{{ selectedEvent.capabilityVersion }}</span></el-descriptions-item>
          <el-descriptions-item label="快照版本">{{ selectedEvent.snapshotVersion || '-' }}</el-descriptions-item>
          <el-descriptions-item label="授权决策"><span class="mono break-anywhere">{{ selectedEvent.policyDecisionId || '-' }}</span></el-descriptions-item>
          <el-descriptions-item label="调用主体摘要"><span class="mono break-anywhere">{{ selectedEvent.subjectDigest || '-' }}</span></el-descriptions-item>
          <el-descriptions-item label="耗时">{{ formatDuration(selectedEvent.durationMs) }}</el-descriptions-item>
        </el-descriptions>
        <section v-if="selectedEvent.detailsJson" class="detail-section"><h4>受控诊断详情</h4><pre class="json-preview">{{ prettyDetails(selectedEvent.detailsJson) }}</pre></section>
        <section v-if="selectedEvent.requestId" class="detail-section">
          <div class="timeline-header"><h4>请求时间线</h4><el-button text type="primary" :loading="timelineLoading" @click="loadTimeline">刷新</el-button></div>
          <el-timeline v-if="timeline.length">
            <el-timeline-item v-for="event in timeline" :key="event.eventId" :timestamp="formatDateTime(event.timestamp)" :type="resultType(event.resultCode)">
              <strong>{{ event.eventType }}</strong><div class="timeline-meta"><span>{{ event.resultCode || '-' }}</span><span>{{ formatDuration(event.durationMs) }}</span></div>
            </el-timeline-item>
          </el-timeline>
          <div v-else-if="!timelineLoading" class="muted">没有找到同一 Request ID 的其他事件。</div>
        </section>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Refresh, Search, Warning } from '@element-plus/icons-vue'
import { gatewayApi } from '@/api/gateway'
import { apiErrorMessage, formatDateTime, formatDuration, isSuccessResult } from '@/utils/format'
import type { AuditEvent } from '@/types/gateway'

const route = useRoute()
const loading = ref(false)
const errorMsg = ref('')
const auditEvents = ref<AuditEvent[]>([])
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const detailOpen = ref(false)
const selectedEvent = ref<AuditEvent>()
const timeline = ref<AuditEvent[]>([])
const timelineLoading = ref(false)
const filters = reactive({
  eventType: '',
  capabilityId: '',
  requestId: '',
  resultCode: '',
  timeRange: [new Date(Date.now() - 24 * 60 * 60 * 1000), new Date()] as [Date, Date]
})
const eventTypes = ['REQUEST_ACCEPTED', 'STARTED', 'SUCCEEDED', 'FAILED', 'PERMISSION_DENIED', 'CLARIFICATION_REQUIRED', 'MANIFEST_IMPORTED', 'CATALOG_PUBLISHED']
const resultCodes = ['SUCCESS', 'AUTHENTICATION_FAILED', 'PERMISSION_DENIED', 'ARGUMENT_VALIDATION_FAILED', 'CAPABILITY_UNAVAILABLE', 'PROVIDER_TIMEOUT', 'PROTOCOL_ERROR', 'RATE_LIMITED']

onMounted(() => {
  if (typeof route.query.requestId === 'string') filters.requestId = route.query.requestId
  if (typeof route.query.capabilityId === 'string') filters.capabilityId = route.query.capabilityId
  loadData()
})

async function loadData() {
  loading.value = true
  errorMsg.value = ''
  try {
    const result = await gatewayApi.audits({
      eventType: filters.eventType || undefined,
      capabilityId: filters.capabilityId || undefined,
      requestId: filters.requestId || undefined,
      resultCode: filters.resultCode || undefined,
      from: filters.timeRange?.[0]?.getTime(),
      to: filters.timeRange?.[1]?.getTime(),
      page: page.value,
      size: pageSize.value
    })
    auditEvents.value = result.items || []
    total.value = Number(result.total || 0)
  } catch (error) { errorMsg.value = apiErrorMessage(error) }
  finally { loading.value = false }
}

function search() { page.value = 1; loadData() }
function handlePageSize() { page.value = 1; loadData() }
function resetFilters() {
  filters.eventType = ''
  filters.capabilityId = ''
  filters.requestId = ''
  filters.resultCode = ''
  filters.timeRange = [new Date(Date.now() - 24 * 60 * 60 * 1000), new Date()]
  search()
}

function resultType(resultCode?: string): 'success' | 'warning' | 'danger' | 'info' {
  if (isSuccessResult(resultCode)) return 'success'
  if (resultCode?.includes('CLARIFICATION') || resultCode === 'RATE_LIMITED') return 'warning'
  return resultCode ? 'danger' : 'info'
}

function openDetail(row: AuditEvent) {
  selectedEvent.value = row
  detailOpen.value = true
  timeline.value = []
  if (row.requestId) loadTimeline()
}

async function loadTimeline() {
  if (!selectedEvent.value?.requestId) return
  timelineLoading.value = true
  try {
    const result = await gatewayApi.audits({ requestId: selectedEvent.value.requestId, page: 1, size: 100 })
    timeline.value = [...result.items].sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime())
  } catch (error) { errorMsg.value = apiErrorMessage(error) }
  finally { timelineLoading.value = false }
}

function prettyDetails(value: string) {
  try { return JSON.stringify(JSON.parse(value), null, 2) }
  catch { return value }
}
</script>

<style scoped>
.audit-surface {
  min-height: 520px;
}

.filter-grid {
  display: grid;
  grid-template-columns: minmax(300px, 1.35fr) repeat(4, minmax(160px, 0.65fr)) auto;
  align-items: end;
  gap: 12px;
  margin-bottom: 16px;
}

.filter-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

:deep(.el-table__row) {
  cursor: pointer;
}

@media (max-width: 1280px) {
  .filter-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .filter-grid {
    grid-template-columns: 1fr;
  }

  .filter-actions,
  .filter-actions .el-button {
    width: 100%;
  }
}
</style>
