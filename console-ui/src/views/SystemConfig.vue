<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h2 class="page-title">系统状态</h2>
        <p class="page-subtitle">查看网关启动时装配的非敏感部署报告；密钥不会展示，这些数据也不等同于实时遥测。</p>
      </div>
      <div class="page-actions"><el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button></div>
    </header>

    <div v-if="sectionErrors.length" class="inline-error" role="alert">
      <el-icon><CircleClose /></el-icon>
      <div class="error-copy">
        <strong>部分系统报告不可用</strong>
        <span v-for="error in sectionErrors" :key="error.section">{{ error.section }}：{{ error.message }}</span>
      </div>
      <el-button text type="primary" @click="loadAll">重试</el-button>
    </div>

    <section class="surface health-panel" aria-labelledby="health-title">
      <header class="surface-header"><div><h3 id="health-title" class="surface-title">Readiness</h3><span class="muted">决定网关是否可以接收流量</span></div><el-tag :type="readiness?.status === 'UP' ? 'success' : 'danger'" effect="plain">{{ readiness?.status || 'UNKNOWN' }}</el-tag></header>
      <div v-if="readiness" class="health-grid"><div v-for="(status, name) in readiness.checks" :key="name" class="health-item"><span>{{ readinessLabels[name] || name }}</span><strong :class="status === 'UP' ? 'success-text' : 'danger-text'"><el-icon><CircleCheck v-if="status === 'UP'" /><CircleClose v-else /></el-icon>{{ status }}</strong></div></div><div v-else class="empty-state"><div><strong>暂无 readiness 数据</strong><span>点击刷新重新检查。</span></div></div>
    </section>

    <div class="section-grid">
      <section class="surface" aria-labelledby="gateway-config-title">
        <header class="surface-header"><div><h3 id="gateway-config-title" class="surface-title">部署配置报告</h3><span class="muted">进程启动时装配的非敏感配置</span></div></header>
        <div class="surface-body"><el-descriptions v-if="config" :column="1" border>
          <el-descriptions-item label="运行环境">{{ config.environment }}</el-descriptions-item>
          <el-descriptions-item label="认证提供者"><el-tag effect="plain">{{ config.authProvider }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="缓存提供者">{{ config.cacheProvider }}</el-descriptions-item>
          <el-descriptions-item label="限流提供者">{{ config.ratelimitProvider }}</el-descriptions-item>
          <el-descriptions-item label="最大请求体">{{ formatBytes(config.maxRequestSizeBytes) }}</el-descriptions-item>
          <el-descriptions-item label="最大响应体">{{ formatBytes(config.maxResponseBytes) }}</el-descriptions-item>
          <el-descriptions-item label="默认超时">{{ formatDuration(config.defaultTimeoutMs) }}</el-descriptions-item>
        </el-descriptions><div v-else class="empty-state"><div><strong>配置不可用</strong><span>请检查网关连接。</span></div></div></div>
      </section>

      <section class="surface" aria-labelledby="cache-title">
        <header class="surface-header"><div><h3 id="cache-title" class="surface-title">缓存启动报告</h3><span class="muted">仅在状态端点提供有效版本和刷新时间时展示</span></div></header>
        <div class="surface-body"><el-descriptions v-if="cacheRuntimeAvailable && cacheStatus" :column="1" border>
          <el-descriptions-item label="提供者">{{ cacheStatus.provider }}</el-descriptions-item>
          <el-descriptions-item label="Redis 地址"><span class="mono">{{ cacheStatus.redisAddress }}</span></el-descriptions-item>
          <el-descriptions-item label="本地 TTL">{{ cacheStatus.localTtlSeconds }} 秒</el-descriptions-item>
          <el-descriptions-item label="活动快照">v{{ cacheStatus.currentSnapshotVersion }}</el-descriptions-item>
          <el-descriptions-item label="最近刷新">{{ formatDateTime(cacheStatus.lastRefreshTimestamp) }}</el-descriptions-item>
        </el-descriptions><div v-else-if="cacheStatus" class="empty-state"><div><strong>实时缓存状态不可用</strong><span>端点返回的是 {{ cacheStatus.provider }} 启动占位数据（快照 v{{ cacheStatus.currentSnapshotVersion }}），不作为缓存命中或刷新状态依据。</span></div></div><div v-else class="empty-state"><div><strong>缓存启动报告不可用</strong><span>请检查网关连接。</span></div></div></div>
      </section>
    </div>

    <section class="surface rate-limit-panel" aria-labelledby="rate-limit-title">
      <header class="surface-header"><div><h3 id="rate-limit-title" class="surface-title">限流启动规则基线</h3><span class="muted">{{ config?.ratelimitProvider === 'stub' ? 'Stub 模式仅提供启动占位规则，运行时治理不可用。' : '来自进程启动副本；服务重启会重置，不代表 Sentinel 实时规则状态。' }}</span></div><el-button text :icon="Refresh" :loading="rulesLoading" @click="loadRules">刷新基线</el-button></header>
      <div class="table-wrap"><el-table v-if="rules.length" :data="rules" stripe style="min-width: 760px"><el-table-column label="类型" width="120"><template #default="{ row }"><el-tag :type="row.type === 'flow' ? 'primary' : 'warning'" effect="plain">{{ row.type }}</el-tag></template></el-table-column><el-table-column prop="resource" label="资源" min-width="320"><template #default="{ row }"><span class="mono">{{ row.resource }}</span></template></el-table-column><el-table-column label="规则属性" min-width="340"><template #default="{ row }"><div class="tag-list"><el-tag v-for="(value, key) in row.properties" :key="key" size="small" effect="plain">{{ key }}={{ value }}</el-tag></div></template></el-table-column></el-table><div v-else class="empty-state"><div><strong>暂无限流规则</strong><span>当前提供者没有返回可展示规则。</span></div></div></div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CircleCheck, CircleClose, Refresh } from '@element-plus/icons-vue'
import { gatewayApi } from '@/api/gateway'
import { apiErrorMessage, formatBytes, formatDateTime, formatDuration } from '@/utils/format'
import type { CacheStatus, GatewayConfig, HealthStatus, RateLimitRule } from '@/types/gateway'

const loading = ref(false)
const rulesLoading = ref(false)
const config = ref<GatewayConfig>()
const cacheStatus = ref<CacheStatus>()
const rules = ref<RateLimitRule[]>([])
const readiness = ref<HealthStatus>()
const sectionErrors = ref<Array<{ section: string; message: string }>>([])
const cacheRuntimeAvailable = computed(() => !!cacheStatus.value
  && cacheStatus.value.provider !== 'stub'
  && cacheStatus.value.currentSnapshotVersion > 0
  && cacheStatus.value.lastRefreshTimestamp > 0)
const readinessLabels: Record<string, string> = { database: 'PostgreSQL', activeSnapshot: '活动快照', requiredSecrets: '必需密钥', adapterInitialization: '适配器初始化' }

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  sectionErrors.value = []
  const results = await Promise.allSettled([gatewayApi.config(), gatewayApi.cacheStatus(), gatewayApi.rateLimitRules(), gatewayApi.readiness()])
  if (results[0].status === 'fulfilled') config.value = results[0].value
  else {
    config.value = undefined
    sectionErrors.value.push({ section: '部署配置报告', message: apiErrorMessage(results[0].reason) })
  }
  if (results[1].status === 'fulfilled') cacheStatus.value = results[1].value
  else {
    cacheStatus.value = undefined
    sectionErrors.value.push({ section: '缓存启动报告', message: apiErrorMessage(results[1].reason) })
  }
  if (results[2].status === 'fulfilled') rules.value = results[2].value
  else {
    rules.value = []
    sectionErrors.value.push({ section: '限流启动规则基线', message: apiErrorMessage(results[2].reason) })
  }
  if (results[3].status === 'fulfilled') readiness.value = results[3].value
  else {
    readiness.value = undefined
    sectionErrors.value.push({ section: 'Readiness', message: apiErrorMessage(results[3].reason) })
  }
  loading.value = false
}

async function loadRules() {
  rulesLoading.value = true
  sectionErrors.value = sectionErrors.value.filter((error) => error.section !== '限流启动规则基线')
  try { rules.value = await gatewayApi.rateLimitRules() }
  catch (error) {
    rules.value = []
    sectionErrors.value.push({ section: '限流启动规则基线', message: apiErrorMessage(error) })
  } finally {
    rulesLoading.value = false
  }
}
</script>

<style scoped>
.health-panel,
.rate-limit-panel {
  margin-bottom: 16px;
}

.inline-error {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  padding: 10px 12px;
  color: var(--gateway-danger);
  background: #fff5f4;
  border: 1px solid #f6c7c3;
  border-radius: 6px;
}

.error-copy {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 2px;
}

.health-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  padding: 8px 16px;
}

.health-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 54px;
  padding: 8px 12px;
  border-right: 1px solid var(--gateway-border);
}

.health-item:last-child {
  border-right: 0;
}

.health-item strong {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.rate-limit-panel > .table-wrap {
  padding-bottom: 0;
}

@media (max-width: 900px) {
  .health-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .health-item:nth-child(2n) {
    border-right: 0;
  }

  .health-item:nth-child(n + 3) {
    border-top: 1px solid var(--gateway-border);
  }
}

@media (max-width: 540px) {
  .health-grid {
    grid-template-columns: 1fr;
  }

  .health-item,
  .health-item:nth-child(2n) {
    border-right: 0;
    border-bottom: 1px solid var(--gateway-border);
  }

  .health-item:last-child {
    border-bottom: 0;
  }
}
</style>
