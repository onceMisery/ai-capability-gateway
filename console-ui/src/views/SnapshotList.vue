<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h2 class="page-title">发布快照</h2>
        <p class="page-subtitle">运行面只消费不可变目录快照；发布和回滚都会产生新的版本。</p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
        <el-button v-if="auth.isAdmin" type="primary" :icon="Promotion" :loading="publishing" @click="openPublishModal">发布快照</el-button>
      </div>
    </header>

    <section class="toolbar snapshot-toolbar">
      <div>
        <span class="field-label">目标环境</span>
        <el-segmented v-model="environment" :options="environmentOptions" @change="loadData" />
      </div>
      <span class="muted">活动快照会作为自然语言路由和结构化调用的目录基线。</span>
    </section>

    <section class="surface">
      <div class="table-wrap">
        <el-table v-if="snapshots.length" :data="snapshots" v-loading="loading" stripe style="min-width: 900px">
          <el-table-column label="快照" width="150">
            <template #default="{ row }"><button class="snapshot-link mono" type="button" @click="openDetail(row)">v{{ row.snapshotVersion }}</button></template>
          </el-table-column>
          <el-table-column prop="environment" label="环境" width="130" />
          <el-table-column label="状态" width="130">
            <template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" effect="light">{{ row.status === 'ACTIVE' ? '活动中' : '已替代' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="能力数" width="120"><template #default="{ row }"><span class="data-number">{{ row.capabilityCount }}</span></template></el-table-column>
          <el-table-column label="发布时间" width="190"><template #default="{ row }">{{ formatDateTime(row.publishedAt) }}</template></el-table-column>
          <el-table-column prop="publishedBy" label="发布主体" width="170" show-overflow-tooltip />
          <el-table-column label="摘要" min-width="230" show-overflow-tooltip><template #default="{ row }"><span class="mono muted">{{ row.digest }}</span></template></el-table-column>
          <el-table-column label="操作" fixed="right" width="180">
            <template #default="{ row }">
              <el-button text type="primary" @click="openDetail(row)">查看内容</el-button>
              <el-button v-if="auth.isAdmin && row.status !== 'ACTIVE'" text type="warning" :loading="rollbackVersion === row.snapshotVersion" @click="rollbackSnapshot(row)">回滚</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else-if="!loading" class="empty-state">
          <div><strong>暂无{{ environment }}快照</strong><span>先完成能力审批，再发布第一个目录版本。</span></div>
        </div>
      </div>
    </section>

    <el-dialog
      v-model="publishOpen"
      title="发布新快照"
      width="min(720px, 92vw)"
      align-center
      destroy-on-close
      @open="onPublishModalOpen"
    >
      <div v-loading="publishLoading" class="publish-modal-body">
        <el-alert
          v-if="publishError"
          :title="publishError"
          type="error"
          show-icon
          :closable="false"
          class="publish-error"
        />

        <div class="publish-section">
          <h4 class="publish-section-title">目标环境</h4>
          <el-radio-group v-model="publishEnvironment">
            <el-radio-button label="production">生产</el-radio-button>
            <el-radio-button label="staging">预发</el-radio-button>
          </el-radio-group>
        </div>

        <div class="publish-section">
          <div class="publish-section-header">
            <h4 class="publish-section-title">
              选择要发布的能力
              <span class="publish-count">（{{ publishForm.capabilities.length }} / {{ approvedCapabilities.length }} 已选）</span>
            </h4>
            <div class="publish-actions">
              <el-button text type="primary" size="small" :disabled="!approvedCapabilities.length" @click="selectAll">全选</el-button>
              <el-button text type="info" size="small" :disabled="!publishForm.capabilities.length" @click="selectNone">清空</el-button>
            </div>
          </div>

          <div v-if="!approvedCapabilities.length && !publishLoading" class="empty-state compact">
            <div><strong>暂无待发布能力</strong><span>当前没有处于「已审批」状态的能力，请先完成审批。</span></div>
          </div>

          <el-checkbox-group v-else v-model="publishForm.capabilities" class="capability-select-list">
            <div
              v-for="cap in approvedCapabilities"
              :key="`${cap.capabilityId}@${cap.version}`"
              class="capability-select-item"
            >
              <el-checkbox :label="`${cap.capabilityId}@${cap.version}`" class="capability-checkbox">
                <div class="capability-info">
                  <div class="capability-title">
                    <span class="capability-name" :title="cap.displayName">{{ cap.displayName || cap.capabilityId }}</span>
                    <el-tag size="small" effect="plain" :type="riskType(cap.risk)">{{ riskLabel(cap.risk) }}</el-tag>
                  </div>
                  <div class="capability-meta">
                    <span class="mono">{{ cap.capabilityId }}</span>
                    <span class="muted">v{{ cap.version }}</span>
                    <el-tag size="small" type="success" effect="light">已审批</el-tag>
                  </div>
                </div>
              </el-checkbox>
            </div>
          </el-checkbox-group>
        </div>

        <div v-if="selectedCapabilityDetails.length" class="publish-section preview-section">
          <h4 class="publish-section-title">发布内容预览</h4>
          <p class="preview-summary">
            即将向 <strong>{{ publishEnvironmentLabel }}</strong> 环境发布
            <strong class="data-number">{{ selectedCapabilityDetails.length }}</strong> 项能力：
          </p>
          <div class="preview-list">
            <div
              v-for="cap in selectedCapabilityDetails"
              :key="`${cap.capabilityId}@${cap.version}`"
              class="preview-item"
            >
              <span class="preview-name" :title="cap.displayName">{{ cap.displayName || cap.capabilityId }}</span>
              <span class="preview-version mono muted">{{ cap.capabilityId }} v{{ cap.version }}</span>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <span class="footer-summary muted">已选 <strong class="data-number">{{ publishForm.capabilities.length }}</strong> 项能力</span>
          <div>
            <el-button @click="publishOpen = false">取消</el-button>
            <el-button type="primary" :loading="publishing" :disabled="!canPublish" @click="confirmPublish">确认发布</el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <el-drawer v-model="detailOpen" title="快照内容" size="min(720px, 100vw)" destroy-on-close>
      <div v-if="detailLoading" v-loading="true" class="detail-loading" />
      <template v-else-if="selectedSnapshot">
        <div class="snapshot-heading">
          <div><span class="eyebrow">{{ selectedSnapshot.environment }}</span><h3 class="mono">v{{ selectedSnapshot.snapshotVersion }}</h3><span class="muted">{{ formatDateTime(selectedSnapshotSummary?.publishedAt) }}</span></div>
          <el-tag :type="selectedSnapshot.status === 'ACTIVE' ? 'success' : 'info'" effect="light">{{ selectedSnapshot.status === 'ACTIVE' ? '活动中' : '已替代' }}</el-tag>
        </div>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="摘要"><span class="mono break-anywhere">{{ selectedSnapshotDetail?.digest || selectedSnapshot.digest }}</span></el-descriptions-item>
          <el-descriptions-item label="策略引用">{{ selectedSnapshotDetail?.policyRef || '-' }}</el-descriptions-item>
          <el-descriptions-item label="能力数量">{{ selectedSnapshotDetail?.capabilityCount ?? selectedSnapshot.capabilityCount }}</el-descriptions-item>
        </el-descriptions>
        <section class="detail-section">
          <h4>包含能力</h4>
          <div class="snapshot-capability-list">
            <div v-for="capability in selectedSnapshotDetail?.capabilities || []" :key="`${capability.id}@${capability.version}`">
              <div class="snapshot-capability-main">
                <span class="capability-name" :title="displayName(capability.id, capability.version)">{{ displayName(capability.id, capability.version) }}</span>
                <span class="mono">{{ capability.id }}</span>
              </div>
              <span class="muted">v{{ capability.version }}</span>
            </div>
          </div>
        </section>
      </template>
      <div v-if="detailError" class="inline-error" role="alert"><el-icon><Warning /></el-icon>{{ detailError }}</div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Promotion, Refresh, Warning } from '@element-plus/icons-vue'
import { gatewayApi } from '@/api/gateway'
import { useAuthStore } from '@/stores/auth'
import { apiErrorMessage, formatDateTime } from '@/utils/format'
import type { CapabilitySummary, SnapshotDetail, SnapshotSummary } from '@/types/gateway'

const auth = useAuthStore()
const loading = ref(false)
const publishing = ref(false)
const rollbackVersion = ref<number>()
const snapshots = ref<SnapshotSummary[]>([])
const environment = ref('production')
const environmentOptions = [
  { label: '生产', value: 'production' },
  { label: '预发', value: 'staging' }
]

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const selectedSnapshot = ref<SnapshotSummary>()
const selectedSnapshotDetail = ref<SnapshotDetail>()
const selectedSnapshotSummary = ref<SnapshotSummary>()

const publishOpen = ref(false)
const publishLoading = ref(false)
const publishError = ref('')
const publishEnvironment = ref('production')
const publishForm = ref({
  capabilities: [] as string[]
})
const approvedCapabilities = ref<CapabilitySummary[]>([])
const capabilityMap = ref<Map<string, CapabilitySummary>>(new Map())

const publishEnvironmentLabel = computed(() =>
  environmentOptions.find(o => o.value === publishEnvironment.value)?.label || publishEnvironment.value
)

const selectedCapabilityDetails = computed(() =>
  publishForm.value.capabilities
    .map(key => capabilityMap.value.get(key))
    .filter((cap): cap is CapabilitySummary => !!cap)
)

const canPublish = computed(() =>
  !publishLoading.value && !!publishForm.value.capabilities.length
)

onMounted(loadData)

async function loadData() {
  loading.value = true
  try { snapshots.value = await gatewayApi.snapshots(environment.value) }
  catch (error) { ElMessage.error(apiErrorMessage(error)) }
  finally { loading.value = false }
}

async function loadCapabilityMap() {
  try {
    const list = await gatewayApi.capabilities()
    const map = new Map<string, CapabilitySummary>()
    for (const cap of list) {
      map.set(`${cap.capabilityId}@${cap.version}`, cap)
    }
    capabilityMap.value = map
  }
  catch (error) {
    console.warn('Failed to load capability display names', error)
  }
}

function openPublishModal() {
  publishEnvironment.value = environment.value
  publishForm.value.capabilities = []
  publishError.value = ''
  publishOpen.value = true
}

async function onPublishModalOpen() {
  publishLoading.value = true
  publishError.value = ''
  publishForm.value.capabilities = []
  try {
    const list = await gatewayApi.capabilities()
    const map = new Map<string, CapabilitySummary>()
    for (const cap of list) {
      map.set(`${cap.capabilityId}@${cap.version}`, cap)
    }
    capabilityMap.value = map
    approvedCapabilities.value = list
      .filter(cap => cap.lifecycle === 'APPROVED')
      .sort((a, b) => (a.displayName || a.capabilityId).localeCompare(b.displayName || b.capabilityId))
  }
  catch (error) {
    publishError.value = apiErrorMessage(error)
  }
  finally {
    publishLoading.value = false
  }
}

function selectAll() {
  publishForm.value.capabilities = approvedCapabilities.value.map(
    cap => `${cap.capabilityId}@${cap.version}`
  )
}

function selectNone() {
  publishForm.value.capabilities = []
}

function riskType(risk: string) {
  switch (risk) {
    case 'CRITICAL': return 'danger'
    case 'HIGH': return 'warning'
    case 'MEDIUM': return 'primary'
    case 'LOW': return 'success'
    case 'READ_ONLY': return 'info'
    default: return 'info'
  }
}

function riskLabel(risk: string) {
  switch (risk) {
    case 'CRITICAL': return '致命'
    case 'HIGH': return '高'
    case 'MEDIUM': return '中'
    case 'LOW': return '低'
    case 'READ_ONLY': return '只读'
    default: return risk || '未知'
  }
}

function displayName(capabilityId: string, version: string) {
  const cap = capabilityMap.value.get(`${capabilityId}@${version}`)
  return cap?.displayName || capabilityId
}

async function confirmPublish() {
  const count = publishForm.value.capabilities.length
  if (!count) return

  try {
    await ElMessageBox.confirm(
      `确认向 ${publishEnvironmentLabel.value} 环境发布 ${count} 项能力？这会改变运行面可见的能力版本。`,
      '发布确认',
      { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' }
    )
  }
  catch { return }

  publishing.value = true
  const capabilities = publishForm.value.capabilities.map(key => {
    const cap = capabilityMap.value.get(key)
    return {
      capabilityId: cap?.capabilityId || key.split('@')[0],
      version: cap?.version || key.split('@')[1]
    }
  })
  try {
    await gatewayApi.publish(publishEnvironment.value, capabilities)
    ElMessage.success('新快照已发布')
    publishOpen.value = false
    if (environment.value === publishEnvironment.value) {
      await loadData()
    }
  }
  catch (error) { ElMessage.error(apiErrorMessage(error)) }
  finally { publishing.value = false }
}

async function rollbackSnapshot(row: SnapshotSummary) {
  try { await ElMessageBox.confirm(`确认将 ${environment.value} 回滚到 v${row.snapshotVersion}？回滚会生成新的活动快照版本。`, '确认回滚', { type: 'warning', confirmButtonText: '确认回滚', cancelButtonText: '取消' }) }
  catch { return }
  rollbackVersion.value = row.snapshotVersion
  try { await gatewayApi.rollback(row.snapshotVersion, environment.value); ElMessage.success('回滚已完成'); await loadData() }
  catch (error) { ElMessage.error(apiErrorMessage(error)) }
  finally { rollbackVersion.value = undefined }
}

async function openDetail(row: SnapshotSummary) {
  selectedSnapshot.value = row
  selectedSnapshotSummary.value = row
  selectedSnapshotDetail.value = undefined
  detailError.value = ''
  detailOpen.value = true
  detailLoading.value = true
  await loadCapabilityMap()
  try { selectedSnapshotDetail.value = await gatewayApi.snapshot(row.snapshotVersion) }
  catch (error) { detailError.value = apiErrorMessage(error) }
  finally { detailLoading.value = false }
}
</script>

<style scoped>
.snapshot-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 16px;
  padding: 12px 0;
}

.snapshot-toolbar .field-label {
  margin-bottom: 6px;
}

.snapshot-link {
  display: inline-flex;
  align-items: center;
  min-width: 44px;
  min-height: 44px;
  padding: 0;
  color: var(--gateway-primary);
  background: transparent;
  border: 0;
  cursor: pointer;
  font-weight: 650;
}

.snapshot-link:hover {
  text-decoration: underline;
}

.snapshot-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.snapshot-heading h3 {
  margin: 0 0 4px;
  font-size: 24px;
}

.eyebrow {
  display: block;
  margin-bottom: 4px;
  color: var(--gateway-primary);
  font-size: 12px;
  font-weight: 650;
  text-transform: uppercase;
}

.snapshot-capability-list {
  display: grid;
  gap: 0;
  border: 1px solid var(--gateway-border);
  border-radius: 6px;
}

.snapshot-capability-list div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 44px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--gateway-border);
}

.snapshot-capability-list div:last-child {
  border-bottom: 0;
}

.snapshot-capability-main {
  display: flex;
  flex-direction: column;
  align-items: flex-start !important;
  gap: 2px !important;
}

.capability-name {
  font-weight: 600;
  color: var(--gateway-text);
}

.detail-loading {
  min-height: 260px;
}

.publish-modal-body {
  max-height: 60vh;
  overflow-y: auto;
  padding-right: 4px;
}

.publish-error {
  margin-bottom: 16px;
}

.publish-section {
  margin-bottom: 20px;
}

.publish-section:last-child {
  margin-bottom: 0;
}

.publish-section-title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 650;
  color: var(--gateway-text);
}

.publish-count {
  font-weight: 400;
  color: var(--gateway-text-muted);
}

.publish-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.publish-actions {
  display: flex;
  gap: 4px;
}

.capability-select-list {
  display: grid;
  gap: 0;
  border: 1px solid var(--gateway-border);
  border-radius: 6px;
  overflow: hidden;
}

.capability-select-item {
  display: flex;
  align-items: center;
  min-height: 56px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--gateway-border);
}

.capability-select-item:last-child {
  border-bottom: 0;
}

.capability-checkbox {
  width: 100%;
  height: 100%;
  margin-right: 0;
}

.capability-checkbox :deep(.el-checkbox__label) {
  width: 100%;
  padding-left: 10px;
}

.capability-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}

.capability-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.capability-title .capability-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.capability-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
}

.preview-section {
  background: var(--gateway-bg-soft, #f5f5f5);
  border: 1px solid var(--gateway-border);
  border-radius: 6px;
  padding: 12px;
}

.preview-summary {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--gateway-text-secondary);
}

.preview-list {
  display: grid;
  gap: 6px;
}

.preview-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  background: #fff;
  border: 1px solid var(--gateway-border);
  border-radius: 4px;
}

.preview-name {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-version {
  font-size: 12px;
  flex-shrink: 0;
}

.dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.footer-summary {
  font-size: 13px;
}
</style>
