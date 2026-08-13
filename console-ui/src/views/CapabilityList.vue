<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h2 class="page-title">能力目录</h2>
        <p class="page-subtitle">管理 Manifest 版本、校验状态和进入活动快照前的治理动作。</p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
        <el-button v-if="auth.isAdmin" type="primary" :icon="Upload" @click="openImport">导入 Manifest</el-button>
      </div>
    </header>

    <section class="catalog-summary surface" aria-label="能力状态摘要">
      <div v-for="item in lifecycleSummary" :key="item.value" class="summary-cell">
        <span class="summary-label">{{ item.label }}</span>
        <strong class="data-number">{{ item.count }}</strong>
      </div>
    </section>

    <section class="surface catalog-surface">
      <div class="surface-body">
        <div class="filter-bar" role="search" aria-label="能力筛选">
          <div class="filter-field filter-field--wide">
            <label class="field-label" for="capability-search">搜索能力</label>
            <el-input id="capability-search" v-model="filters.search" clearable :prefix-icon="Search" placeholder="搜索 ID、名称、描述或团队" />
          </div>
          <div class="filter-field">
            <label class="field-label" for="lifecycle-filter">生命周期</label>
            <el-select id="lifecycle-filter" v-model="filters.lifecycle" clearable placeholder="全部状态" style="width: 100%">
              <el-option v-for="item in lifecycleOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </div>
          <div class="filter-field">
            <label class="field-label" for="risk-filter">风险等级</label>
            <el-select id="risk-filter" v-model="filters.risk" clearable placeholder="全部风险" style="width: 100%">
              <el-option v-for="risk in riskOptions" :key="risk" :label="riskLabel(risk)" :value="risk" />
            </el-select>
          </div>
          <el-button text :disabled="!hasFilters" @click="resetFilters">清除筛选</el-button>
        </div>

        <div v-if="errorMsg" class="inline-error" role="alert">
          <el-icon><Warning /></el-icon>{{ errorMsg }}
          <el-button text type="primary" @click="loadData">重试</el-button>
        </div>

        <div class="table-wrap">
          <el-table v-if="filteredCapabilities.length" :data="pagedCapabilities" v-loading="loading" stripe style="min-width: 980px">
            <el-table-column label="能力" min-width="300">
              <template #default="{ row }">
                <button class="capability-link" type="button" @click="openDetail(row)">
                  <strong>{{ row.displayName }}</strong>
                  <span class="mono">{{ row.capabilityId }}</span>
                </button>
              </template>
            </el-table-column>
            <el-table-column prop="version" label="版本" width="110">
              <template #default="{ row }"><span class="mono">v{{ row.version }}</span></template>
            </el-table-column>
            <el-table-column label="风险" width="120">
              <template #default="{ row }"><el-tag :type="riskType(row.risk)" effect="plain">{{ riskLabel(row.risk) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="状态" width="128">
              <template #default="{ row }"><el-tag :type="lifecycleType(row.lifecycle)" effect="light">{{ lifecycleLabel(row.lifecycle) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="负责人" width="170">
              <template #default="{ row }"><span>{{ row.ownerTeam }}</span></template>
            </el-table-column>
            <el-table-column label="最近更新" width="175">
              <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" fixed="right" width="230">
              <template #default="{ row }">
                <div class="row-actions">
                  <el-button text type="primary" @click="openDetail(row)">详情</el-button>
                  <el-button v-if="auth.isAdmin && nextAction(row) === 'validate'" text type="primary" :loading="isBusy(row, 'validate')" @click="validate(row)">校验</el-button>
                  <el-button v-if="auth.isAdmin && nextAction(row) === 'approve'" text type="primary" :loading="isBusy(row, 'approve')" @click="approve(row)">审批</el-button>
                  <el-button v-if="auth.isAdmin && nextAction(row) === 'suspend'" text type="danger" :loading="isBusy(row, 'suspend')" @click="suspend(row)">停用</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
          <div v-else-if="!loading" class="empty-state">
            <div>
              <strong>{{ capabilities.length ? '没有匹配的能力' : '能力目录为空' }}</strong>
              <span>{{ capabilities.length ? '调整筛选条件，或清除筛选查看全部。' : '导入第一个 Manifest，开始建立受治理能力目录。' }}</span>
              <el-button v-if="!capabilities.length && auth.isAdmin" type="primary" class="empty-action" @click="openImport">导入 Manifest</el-button>
            </div>
          </div>
        </div>

        <div v-if="filteredCapabilities.length" class="pagination-row">
          <span class="muted">共 {{ filteredCapabilities.length }} 项能力</span>
          <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="filteredCapabilities.length" :page-sizes="[10, 20, 50]" layout="sizes, prev, pager, next" />
        </div>
      </div>
    </section>

    <el-drawer v-model="detailOpen" title="能力详情" size="min(620px, 100vw)" destroy-on-close>
      <div v-if="detailLoading" v-loading="true" class="detail-loading" />
      <template v-else-if="selectedManifest">
        <div class="detail-heading">
          <div>
            <span class="eyebrow">{{ selectedManifest.metadata.id }}</span>
            <h3>{{ selectedManifest.spec.displayName }}</h3>
            <span class="mono">v{{ selectedManifest.metadata.version }}</span>
          </div>
          <el-tag :type="lifecycleType(selectedLifecycle)" effect="light">{{ lifecycleLabel(selectedLifecycle) }}</el-tag>
        </div>
        <el-descriptions :column="1" border class="detail-descriptions">
          <el-descriptions-item label="业务描述">{{ selectedManifest.spec.description }}</el-descriptions-item>
          <el-descriptions-item label="风险等级"><el-tag :type="riskType(selectedManifest.spec.risk)" effect="plain">{{ riskLabel(selectedManifest.spec.risk) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="负责团队">{{ selectedManifest.metadata.owner.team }}</el-descriptions-item>
          <el-descriptions-item label="负责人联系">{{ selectedManifest.metadata.owner.contact }}</el-descriptions-item>
          <el-descriptions-item label="标签">{{ selectedManifest.metadata.tags?.join('、') || '无' }}</el-descriptions-item>
        </el-descriptions>
        <section class="detail-section">
          <h4>授权要求</h4>
          <div v-if="selectedManifest.spec.authorization?.permissions?.length" class="tag-list">
            <el-tag v-for="permission in selectedManifest.spec.authorization.permissions" :key="permission" effect="plain">{{ permission }}</el-tag>
          </div>
          <span v-else class="muted">未声明额外权限</span>
        </section>
        <section class="detail-section">
          <h4>输入 Schema</h4>
          <pre class="json-preview">{{ formatJson(selectedManifest.spec.inputSchema) }}</pre>
        </section>
        <section v-if="selectedManifest.spec.examples?.positive?.length" class="detail-section">
          <h4>路由示例</h4>
          <ul class="example-list"><li v-for="example in selectedManifest.spec.examples.positive" :key="example">{{ example }}</li></ul>
        </section>
        <div v-if="detailError" class="inline-error" role="alert"><el-icon><Warning /></el-icon>{{ detailError }}</div>
        <div class="drawer-actions">
          <el-button v-if="auth.isAdmin && selectedSummary && nextAction(selectedSummary) === 'validate'" type="primary" :loading="isBusy(selectedSummary, 'validate')" @click="validate(selectedSummary)">重新校验</el-button>
          <el-button v-if="auth.isAdmin && selectedSummary && nextAction(selectedSummary) === 'approve'" type="primary" :loading="isBusy(selectedSummary, 'approve')" @click="approve(selectedSummary)">审批通过</el-button>
          <el-button v-if="auth.isAdmin && selectedSummary && nextAction(selectedSummary) === 'suspend'" type="danger" :loading="isBusy(selectedSummary, 'suspend')" @click="suspend(selectedSummary)">停用能力</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="importOpen" title="导入 Capability Manifest" width="min(720px, calc(100vw - 24px))" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="Manifest JSON" required>
          <el-input v-model="importText" type="textarea" :rows="14" spellcheck="false" placeholder="粘贴 JSON 格式的 Capability Manifest" />
          <div class="form-helper">后端当前接受 JSON；导入后仍需校验、审批并发布到快照。</div>
        </el-form-item>
      </el-form>
      <div v-if="importParseError" class="inline-error" role="alert"><el-icon><Warning /></el-icon>{{ importParseError }}</div>
      <el-alert v-if="importResult" :type="importResult.validationReport?.valid ? 'success' : 'warning'" show-icon :closable="false" :title="importResult.validationReport?.valid ? 'Manifest 已导入并通过校验' : 'Manifest 已导入，请处理校验结果'">
        <template v-if="importResult.validationReport" #default>
          <div v-if="importResult.validationReport.errors.length">错误：{{ importResult.validationReport.errors.join('；') }}</div>
          <div v-if="importResult.validationReport.warnings.length">警告：{{ importResult.validationReport.warnings.join('；') }}</div>
        </template>
      </el-alert>
      <template #footer>
        <el-button @click="importOpen = false">关闭</el-button>
        <el-button type="primary" :loading="importLoading" :disabled="!!importResult" @click="submitImport">导入并校验</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="validationOpen" title="校验结果" width="min(560px, calc(100vw - 24px))">
      <el-result v-if="validationResult" :icon="validationResult.valid ? 'success' : 'warning'" :title="validationResult.valid ? '校验通过' : '校验未通过'">
        <template #sub-title>
          <div v-if="validationResult.errors.length" class="result-list result-list--error"><strong>错误</strong><span v-for="item in validationResult.errors" :key="item">{{ item }}</span></div>
          <div v-if="validationResult.warnings.length" class="result-list"><strong>警告</strong><span v-for="item in validationResult.warnings" :key="item">{{ item }}</span></div>
        </template>
      </el-result>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search, Upload, Warning } from '@element-plus/icons-vue'
import { gatewayApi } from '@/api/gateway'
import { useAuthStore } from '@/stores/auth'
import { apiErrorMessage, formatDateTime } from '@/utils/format'
import type { CapabilityLifecycle, CapabilityManifest, CapabilitySummary, RiskLevel, ValidationReport, ManifestMutationResult } from '@/types/gateway'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const errorMsg = ref('')
const capabilities = ref<CapabilitySummary[]>([])
const page = ref(1)
const pageSize = ref(20)
const filters = reactive({ search: '', lifecycle: '', risk: '' })
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const selectedManifest = ref<CapabilityManifest>()
const selectedSummary = ref<CapabilitySummary>()
const importOpen = ref(false)
const importLoading = ref(false)
const importText = ref('')
const importParseError = ref('')
const importResult = ref<ManifestMutationResult>()
const validationOpen = ref(false)
const validationResult = ref<ValidationReport>()
const busy = ref<Record<string, boolean>>({})

const lifecycleOptions: Array<{ label: string; value: CapabilityLifecycle }> = [
  { label: '草稿', value: 'DRAFT' },
  { label: '已校验', value: 'VALIDATED' },
  { label: '已审批', value: 'APPROVED' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '已停用', value: 'SUSPENDED' },
  { label: '已退役', value: 'RETIRED' },
  { label: '校验拒绝', value: 'REJECTED' }
]
const riskOptions = ['READ_ONLY', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

const filteredCapabilities = computed(() => {
  const keyword = filters.search.trim().toLowerCase()
  return capabilities.value.filter((item) => {
    if (filters.lifecycle && item.lifecycle !== filters.lifecycle) return false
    if (filters.risk && item.risk !== filters.risk) return false
    if (!keyword) return true
    return [item.capabilityId, item.displayName, item.description, item.ownerTeam, ...(item.tags || [])]
      .some((value) => String(value).toLowerCase().includes(keyword))
  })
})
const pagedCapabilities = computed(() => filteredCapabilities.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
const hasFilters = computed(() => !!filters.search || !!filters.lifecycle || !!filters.risk)
const lifecycleSummary = computed(() => lifecycleOptions.map((item) => ({ ...item, count: capabilities.value.filter((capability) => capability.lifecycle === item.value).length })))
const selectedLifecycle = computed<CapabilityLifecycle>(() => selectedSummary.value?.lifecycle || 'DRAFT')

onMounted(async () => {
  if (typeof route.query.lifecycle === 'string') filters.lifecycle = route.query.lifecycle
  await loadData()
  if (route.query.action === 'import') openImport()
})

watch(() => [filters.search, filters.lifecycle, filters.risk], () => { page.value = 1 })

async function loadData() {
  loading.value = true
  errorMsg.value = ''
  try {
    capabilities.value = await gatewayApi.capabilities()
  } catch (error) {
    errorMsg.value = apiErrorMessage(error)
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  filters.search = ''
  filters.lifecycle = ''
  filters.risk = ''
}

function lifecycleLabel(value: string) { return lifecycleOptions.find((item) => item.value === value)?.label || value }
function lifecycleType(value: string): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  return ({ DRAFT: 'info', VALIDATED: 'warning', APPROVED: 'warning', PUBLISHED: 'success', SUSPENDED: 'danger', RETIRED: 'info', REJECTED: 'danger' } as Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'>)[value] || 'info'
}
function riskLabel(value: string) { return value === 'READ_ONLY' ? '只读' : ({ LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险', CRITICAL: '严重' } as Record<string, string>)[value] || value }
function riskType(value: RiskLevel): 'success' | 'warning' | 'danger' | 'info' { return value === 'READ_ONLY' || value === 'LOW' ? 'success' : value === 'MEDIUM' ? 'warning' : value === 'HIGH' || value === 'CRITICAL' ? 'danger' : 'info' }
function nextAction(row: CapabilitySummary): 'validate' | 'approve' | 'suspend' | undefined {
  if (row.lifecycle === 'DRAFT') return 'validate'
  if (row.lifecycle === 'VALIDATED') return 'approve'
  if (row.lifecycle === 'PUBLISHED') return 'suspend'
  return undefined
}
function operationKey(row: CapabilitySummary, action: string) { return `${row.capabilityId}@${row.version}:${action}` }
function isBusy(row: CapabilitySummary, action: string) { return !!busy.value[operationKey(row, action)] }
function setBusy(row: CapabilitySummary, action: string, value: boolean) { busy.value[operationKey(row, action)] = value }

async function openDetail(row: CapabilitySummary) {
  selectedSummary.value = row
  selectedManifest.value = undefined
  detailError.value = ''
  detailOpen.value = true
  detailLoading.value = true
  try {
    selectedManifest.value = await gatewayApi.capability(row.capabilityId, row.version)
  } catch (error) {
    detailError.value = apiErrorMessage(error)
  } finally {
    detailLoading.value = false
  }
}

function formatJson(value: unknown) { return JSON.stringify(value, null, 2) }

async function validate(row: CapabilitySummary) {
  setBusy(row, 'validate', true)
  try {
    const result = await gatewayApi.validateCapability(row.capabilityId, row.version)
    validationResult.value = result.validationReport || { valid: true, errors: [], warnings: [] }
    validationOpen.value = true
    await loadData()
  } catch (error) { ElMessage.error(apiErrorMessage(error)) }
  finally { setBusy(row, 'validate', false) }
}

async function approve(row: CapabilitySummary) {
  try {
    await ElMessageBox.confirm(`确认审批 ${row.displayName} v${row.version}？审批后可进入发布快照。`, '确认审批', { type: 'warning', confirmButtonText: '审批通过', cancelButtonText: '取消' })
  } catch { return }
  setBusy(row, 'approve', true)
  try { await gatewayApi.approveCapability(row.capabilityId, row.version); ElMessage.success('能力已审批'); await loadData() }
  catch (error) { ElMessage.error(apiErrorMessage(error)) }
  finally { setBusy(row, 'approve', false) }
}

async function suspend(row: CapabilitySummary) {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('请输入停用原因，至少 4 个字符。', `停用 ${row.displayName}`, { inputPattern: /.{4,}/, inputErrorMessage: '请输入至少 4 个字符', confirmButtonText: '确认停用', cancelButtonText: '取消', type: 'warning' })
    reason = result.value
  } catch { return }
  setBusy(row, 'suspend', true)
  try { await gatewayApi.suspendCapability(row.capabilityId, reason); ElMessage.success('能力已停用'); await loadData() }
  catch (error) { ElMessage.error(apiErrorMessage(error)) }
  finally { setBusy(row, 'suspend', false) }
}

function openImport() {
  importText.value = ''
  importParseError.value = ''
  importResult.value = undefined
  importOpen.value = true
}

async function submitImport() {
  importParseError.value = ''
  let manifest: CapabilityManifest
  try {
    const parsed: unknown = JSON.parse(importText.value)
    if (!parsed || typeof parsed !== 'object' || !('metadata' in parsed) || !('spec' in parsed)) throw new Error('缺少 metadata 或 spec')
    manifest = parsed as CapabilityManifest
  } catch (error) {
    importParseError.value = `JSON 格式无效：${error instanceof Error ? error.message : '无法解析'}`
    return
  }
  importLoading.value = true
  try {
    importResult.value = await gatewayApi.importManifest(manifest)
    ElMessage.success('Manifest 已导入')
    await loadData()
  } catch (error) { importParseError.value = apiErrorMessage(error) }
  finally { importLoading.value = false }
}
</script>

<style scoped>
.catalog-summary {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  margin-bottom: 16px;
}

.summary-cell {
  display: grid;
  min-height: 76px;
  padding: 12px 16px;
  align-content: center;
  border-right: 1px solid var(--gateway-border);
}

.summary-cell:last-child {
  border-right: 0;
}

.summary-label {
  color: var(--gateway-text-muted);
  font-size: 12px;
}

.summary-cell strong {
  margin-top: 3px;
  font-size: 21px;
}

.catalog-surface {
  min-height: 440px;
}

.capability-link {
  display: grid;
  width: 100%;
  padding: 0;
  color: var(--gateway-text);
  background: transparent;
  border: 0;
  cursor: pointer;
  text-align: left;
}

.capability-link strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.capability-link span {
  margin-top: 3px;
  color: var(--gateway-text-muted);
  font-size: 12px;
}

.capability-link:hover strong {
  color: var(--gateway-primary);
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 0;
  white-space: nowrap;
}

.empty-action {
  display: block;
  margin: 16px auto 0;
}

.detail-loading {
  min-height: 260px;
}

.eyebrow {
  display: block;
  margin-bottom: 5px;
  color: var(--gateway-primary);
  font-size: 12px;
  font-weight: 650;
}

.detail-heading h3 {
  margin: 0 0 4px;
  font-size: 21px;
}

.detail-heading .mono {
  color: var(--gateway-text-muted);
  font-size: 12px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.example-list {
  margin: 0;
  padding-left: 20px;
  color: var(--gateway-text-secondary);
}

.example-list li + li {
  margin-top: 6px;
}

.form-helper {
  margin-top: 6px;
  color: var(--gateway-text-muted);
  font-size: 12px;
}

.result-list {
  display: grid;
  gap: 6px;
  margin-top: 8px;
  text-align: left;
}

.result-list--error {
  color: var(--gateway-danger);
}

@media (max-width: 1000px) {
  .catalog-summary {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .summary-cell:nth-child(4) {
    border-right: 0;
  }

  .summary-cell:nth-child(n + 5) {
    border-top: 1px solid var(--gateway-border);
  }
}

@media (max-width: 620px) {
  .catalog-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .summary-cell:nth-child(2n) {
    border-right: 0;
  }

  .summary-cell:nth-child(n + 3) {
    border-top: 1px solid var(--gateway-border);
  }
}
</style>
