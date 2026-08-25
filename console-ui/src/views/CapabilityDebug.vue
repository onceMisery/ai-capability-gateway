<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h2 class="page-title">能力调试</h2>
        <p class="page-subtitle">通过自然语言验证已发布能力，查看网关返回的结构化结果和执行状态。</p>
      </div>
      <div class="page-actions">
        <el-button :icon="Delete" :disabled="loading || !result" @click="clearResult">清空结果</el-button>
      </div>
    </header>

    <el-alert
      class="debug-notice"
      type="info"
      show-icon
      :closable="false"
      title="调试请求仍会经过正常的身份、策略、快照和能力执行链路。"
    >
      <template #default>请勿在输入内容中填写密码、令牌或其他敏感信息。</template>
    </el-alert>

    <div class="debug-layout">
      <section class="surface request-panel" aria-labelledby="request-title">
        <header class="surface-header">
          <div>
            <h3 id="request-title" class="surface-title">自然语言请求</h3>
            <span class="muted">输入一个完整、可执行的业务意图</span>
          </div>
          <el-tag effect="plain">POST /api/v1/natural-language/queries</el-tag>
        </header>

        <div class="surface-body">
          <el-form label-position="top" @submit.prevent="submit">
            <el-form-item label="请求内容" required>
              <el-input
                ref="inputRef"
                v-model="queryText"
                type="textarea"
                :rows="8"
                maxlength="8192"
                show-word-limit
                resize="vertical"
                placeholder="例如：查询本月华东区域的订单总量"
                @keydown.ctrl.enter.prevent="submit"
              />
              <span class="field-helper">支持 Ctrl + Enter 发送。调试页面默认使用当前登录管理员身份。</span>
            </el-form-item>

            <div class="request-meta">
              <div>
                <span class="meta-label">请求 ID</span>
                <span class="mono">{{ currentRequestId }}</span>
              </div>
              <div>
                <span class="meta-label">语言 / 时区</span>
                <span>zh-CN / Asia/Shanghai</span>
              </div>
            </div>

            <div class="form-actions">
              <el-button
                type="primary"
                :icon="Promotion"
                :loading="loading"
                :disabled="!queryText.trim()"
                @click="submit"
              >
                发送请求
              </el-button>
            </div>
          </el-form>
        </div>
      </section>

      <section class="surface result-panel" aria-labelledby="result-title">
        <header class="surface-header">
          <div>
            <h3 id="result-title" class="surface-title">执行结果</h3>
            <span class="muted">结果来自自然语言网关执行链路</span>
          </div>
          <el-tag v-if="result" :type="resultTagType(result.status)" effect="plain">
            {{ resultStatusLabel(result.status) }}
          </el-tag>
        </header>

        <div v-if="loading" class="result-loading">
          <el-skeleton :rows="6" animated />
          <span>正在解析意图、匹配能力并执行请求...</span>
        </div>

        <div v-else-if="result" class="result-content">
          <div v-if="result.status === 'CLARIFICATION_REQUIRED'" class="clarification-box">
            <div class="result-icon"><el-icon><ChatLineSquare /></el-icon></div>
            <div>
              <strong>需要补充信息</strong>
              <p>{{ result.question || result.summary || '请补充更多信息后重试。' }}</p>
            </div>
          </div>

          <div v-else-if="result.status === 'NO_MATCH'" class="empty-result">
            <el-icon><Search /></el-icon>
            <strong>没有匹配到可用能力</strong>
            <span>请换一种表达，或确认目标能力已经发布到当前目录。</span>
          </div>

          <div v-else-if="result.status === 'ERROR' || result.status === 'INVALID'" class="error-result" role="alert">
            <div class="result-icon"><el-icon><CircleClose /></el-icon></div>
            <div>
              <strong>{{ localizedErrorMessage(result.errorCode, result.message) }}</strong>
              <p>{{ result.message ? localizedErrorMessage(undefined, result.message) : '网关未能完成本次请求。' }}</p>
            </div>
          </div>

          <div v-else-if="result.status === 'COMPLETED'" class="success-result">
            <div class="result-icon"><el-icon><CircleCheck /></el-icon></div>
            <div>
              <strong>请求已完成</strong>
              <p>{{ result.summary || '能力执行成功。' }}</p>
            </div>
          </div>

          <el-descriptions :column="2" border class="result-meta">
            <el-descriptions-item label="状态">{{ resultStatusLabel(result.status) }}</el-descriptions-item>
            <el-descriptions-item label="请求 ID"><span class="mono">{{ result.requestId || currentRequestId }}</span></el-descriptions-item>
            <el-descriptions-item label="快照版本">{{ result.snapshotVersion ? `v${result.snapshotVersion}` : '-' }}</el-descriptions-item>
            <el-descriptions-item label="交互 ID"><span class="mono">{{ result.interactionId || '-' }}</span></el-descriptions-item>
          </el-descriptions>

          <div v-if="result.status === 'CLARIFICATION_REQUIRED'" class="clarification-followup">
            <el-input
              v-model="followupText"
              type="textarea"
              :rows="3"
              maxlength="8192"
              placeholder="补充回答后继续当前交互"
              @keydown.ctrl.enter.prevent="continueClarification"
            />
            <el-button
              type="primary"
              :loading="continuing"
              :disabled="!followupText.trim() || !result.interactionId"
              @click="continueClarification"
            >
              继续交互
            </el-button>
          </div>

          <details v-if="result.data !== undefined" class="payload-section" open>
            <summary>返回数据</summary>
            <pre>{{ formatJson(result.data) }}</pre>
          </details>
          <details v-if="result.capability !== undefined" class="payload-section">
            <summary>匹配能力</summary>
            <pre>{{ formatJson(result.capability) }}</pre>
          </details>
          <details v-if="result.execution !== undefined" class="payload-section">
            <summary>执行信息</summary>
            <pre>{{ formatJson(result.execution) }}</pre>
          </details>
        </div>

        <div v-else class="empty-state result-empty">
          <div>
            <el-icon><DataAnalysis /></el-icon>
            <strong>还没有调试结果</strong>
            <span>在左侧输入自然语言请求，发送后查看匹配能力和执行结果。</span>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { CircleCheck, CircleClose, ChatLineSquare, DataAnalysis, Delete, Promotion, Search } from '@element-plus/icons-vue'
import { ElMessage, type InputInstance } from 'element-plus'
import { gatewayApi } from '@/api/gateway'
import { apiErrorMessage, localizedErrorMessage } from '@/utils/format'
import type { NaturalLanguageQueryResult } from '@/types/gateway'

const inputRef = ref<InputInstance>()
const queryText = ref('')
const followupText = ref('')
const loading = ref(false)
const continuing = ref(false)
const result = ref<NaturalLanguageQueryResult>()
const requestId = ref(createRequestId())

const currentRequestId = computed(() => requestId.value)

function createRequestId() {
  const uuid = typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
  return `console-debug-${uuid}`
}

async function submit() {
  const text = queryText.value.trim()
  if (!text || loading.value) return
  loading.value = true
  result.value = undefined
  followupText.value = ''
  requestId.value = createRequestId()
  try {
    result.value = await gatewayApi.naturalLanguageQuery(requestId.value, text)
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    loading.value = false
    await nextTick()
    inputRef.value?.focus()
  }
}

async function continueClarification() {
  const text = followupText.value.trim()
  const interactionId = result.value?.interactionId
  if (!text || !interactionId || continuing.value) return
  continuing.value = true
  try {
    result.value = await gatewayApi.continueClarification(interactionId, text)
    followupText.value = ''
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    continuing.value = false
  }
}

function clearResult() {
  result.value = undefined
  followupText.value = ''
}

function formatJson(value: unknown) {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function resultStatusLabel(status: string) {
  return {
    COMPLETED: '已完成',
    CLARIFICATION_REQUIRED: '需要澄清',
    NO_MATCH: '无匹配',
    ERROR: '失败',
    INVALID: '无效请求'
  }[status] || status
}

function resultTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'CLARIFICATION_REQUIRED' || status === 'NO_MATCH') return 'warning'
  if (status === 'ERROR' || status === 'INVALID') return 'danger'
  return 'info'
}
</script>

<style scoped>
.debug-notice {
  margin-bottom: 16px;
}

.debug-layout {
  display: grid;
  grid-template-columns: minmax(320px, 0.85fr) minmax(420px, 1.15fr);
  gap: 16px;
  align-items: start;
}

.request-panel,
.result-panel {
  min-width: 0;
}

.surface-header .el-tag {
  max-width: 100%;
}

.surface-body {
  padding: 20px;
}

.field-helper {
  display: block;
  margin-top: 8px;
  color: var(--gateway-text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.request-meta {
  display: grid;
  gap: 10px;
  margin: 18px 0;
  padding: 12px;
  color: var(--gateway-text-secondary);
  background: var(--gateway-surface-subtle);
  border: 1px solid var(--gateway-border);
  border-radius: 6px;
  font-size: 12px;
}

.request-meta > div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
}

.meta-label {
  color: var(--gateway-text-muted);
}

.form-actions {
  display: flex;
  justify-content: flex-end;
}

.result-content {
  padding: 20px;
}

.result-loading {
  display: grid;
  gap: 14px;
  padding: 24px;
  color: var(--gateway-text-muted);
}

.result-loading > span {
  font-size: 13px;
}

.success-result,
.clarification-box,
.error-result {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 16px;
  border: 1px solid var(--gateway-border);
  border-radius: 6px;
}

.success-result {
  background: color-mix(in srgb, var(--gateway-success) 7%, white);
}

.clarification-box {
  background: color-mix(in srgb, var(--gateway-warning) 8%, white);
}

.error-result {
  background: color-mix(in srgb, var(--gateway-danger) 7%, white);
}

.result-icon {
  display: grid;
  flex: 0 0 28px;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  color: var(--gateway-text);
  background: var(--gateway-surface);
  font-size: 17px;
}

.success-result strong {
  color: var(--gateway-success);
}

.clarification-box strong {
  color: var(--gateway-warning);
}

.error-result strong {
  color: var(--gateway-danger);
}

.success-result p,
.clarification-box p,
.error-result p {
  margin: 6px 0 0;
  color: var(--gateway-text-secondary);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.result-meta {
  margin-top: 16px;
}

.clarification-followup {
  display: grid;
  gap: 10px;
  margin-top: 16px;
}

.clarification-followup .el-button {
  justify-self: end;
}

.payload-section {
  margin-top: 16px;
  border: 1px solid var(--gateway-border);
  border-radius: 6px;
  overflow: hidden;
}

.payload-section summary {
  padding: 12px 14px;
  color: var(--gateway-text-secondary);
  background: var(--gateway-surface-subtle);
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
}

.payload-section pre {
  max-height: 360px;
  margin: 0;
  padding: 14px;
  overflow: auto;
  color: var(--gateway-text);
  background: var(--gateway-surface);
  font: 12px/1.6 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

.empty-result {
  display: grid;
  place-items: center;
  gap: 8px;
  min-height: 180px;
  padding: 24px;
  color: var(--gateway-text-muted);
  text-align: center;
}

.empty-result .el-icon,
.result-empty .el-icon {
  color: var(--gateway-text-secondary);
  font-size: 28px;
}

.empty-result strong,
.result-empty strong {
  color: var(--gateway-text);
}

.result-empty {
  min-height: 360px;
}

.result-empty > div {
  display: grid;
  place-items: center;
  gap: 8px;
  max-width: 300px;
  text-align: center;
}

.result-empty span {
  color: var(--gateway-text-muted);
  line-height: 1.6;
}

@media (max-width: 1000px) {
  .debug-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 540px) {
  .surface-body,
  .result-content {
    padding: 16px;
  }

  .request-meta > div {
    display: grid;
    gap: 4px;
  }

  .form-actions,
  .form-actions .el-button,
  .clarification-followup .el-button {
    width: 100%;
  }
}
</style>
