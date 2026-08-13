<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h2 class="page-title">发布快照</h2>
        <p class="page-subtitle">运行面只消费不可变目录快照；发布和回滚都会产生新的版本。</p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
        <el-button v-if="auth.isAdmin" type="primary" :icon="Promotion" :loading="publishing" @click="publishSnapshot">发布快照</el-button>
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

    <el-drawer v-model="detailOpen" title="快照内容" size="min(620px, 100vw)" destroy-on-close>
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
        <section class="detail-section"><h4>包含能力</h4><div class="snapshot-capability-list"><div v-for="capability in selectedSnapshotDetail?.capabilities || []" :key="`${capability.id}@${capability.version}`"><span class="mono">{{ capability.id }}</span><span class="muted">v{{ capability.version }}</span></div></div></section>
      </template>
      <div v-if="detailError" class="inline-error" role="alert"><el-icon><Warning /></el-icon>{{ detailError }}</div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Promotion, Refresh, Warning } from '@element-plus/icons-vue'
import { gatewayApi } from '@/api/gateway'
import { useAuthStore } from '@/stores/auth'
import { apiErrorMessage, formatDateTime } from '@/utils/format'
import type { SnapshotDetail, SnapshotSummary } from '@/types/gateway'

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

onMounted(loadData)

async function loadData() {
  loading.value = true
  try { snapshots.value = await gatewayApi.snapshots(environment.value) }
  catch (error) { ElMessage.error(apiErrorMessage(error)) }
  finally { loading.value = false }
}

async function publishSnapshot() {
  try { await ElMessageBox.confirm(`确认发布 ${environment.value} 环境的新快照？这会改变运行面可见的能力版本。`, '发布快照', { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' }) }
  catch { return }
  publishing.value = true
  try { await gatewayApi.publish(environment.value); ElMessage.success('新快照已发布'); await loadData() }
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
  try { selectedSnapshotDetail.value = await gatewayApi.snapshot(row.snapshotVersion) }
  catch (error) { detailError.value = apiErrorMessage(error) }
  finally { detailLoading.value = false }
}
</script>

<style scoped>
.snapshot-toolbar {
  justify-content: space-between;
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

.detail-section {
  margin-top: 24px;
}

.detail-section h4 {
  margin: 0 0 10px;
  font-size: 14px;
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

.detail-loading {
  min-height: 260px;
}

.break-anywhere {
  overflow-wrap: anywhere;
}
</style>
