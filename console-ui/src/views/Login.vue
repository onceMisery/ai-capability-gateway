<template>
  <main class="login-page">
    <section class="login-brand" aria-labelledby="product-name">
      <div class="product-mark" aria-hidden="true">AI</div>
      <p class="product-context">TRUSTED EXECUTION PLANE</p>
      <h1 id="product-name">AI Capability Gateway</h1>
      <p>治理控制台</p>
      <dl class="runtime-facts">
        <div>
          <dt>认证模式</dt>
          <dd>{{ authModeLabel }}</dd>
        </div>
        <div>
          <dt>控制台</dt>
          <dd>{{ consoleEnabled ? '可用' : '不可用' }}</dd>
        </div>
      </dl>
    </section>

    <section class="login-panel" aria-labelledby="login-title">
      <div class="login-form-wrap">
        <div class="login-heading">
          <span class="eyebrow">管理访问</span>
          <h2 id="login-title">登录治理控制台</h2>
        </div>

        <el-alert
          v-if="errorMsg"
          class="login-alert"
          :title="errorMsg"
          type="error"
          show-icon
          :closable="true"
          @close="errorMsg = ''"
        />

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="handleLogin">
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model.trim="form.username"
              size="large"
              autocomplete="username"
              placeholder="请输入用户名"
              :prefix-icon="User"
              autofocus
            />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              autocomplete="current-password"
              placeholder="请输入密码"
              :prefix-icon="Lock"
              show-password
            />
          </el-form-item>
          <el-button
            class="login-button"
            type="primary"
            size="large"
            native-type="submit"
            :loading="loading"
            :disabled="!consoleEnabled"
          >
            登录
          </el-button>
        </el-form>

        <p v-if="auth.authMode === 'stub'" class="mode-notice">
          当前为 Stub 开发模式，请勿用于生产环境。
        </p>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { apiErrorMessage } from '@/utils/format'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const loading = ref(false)
const errorMsg = ref('')
const consoleEnabled = ref(true)
const form = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const authModeLabel = computed(() => auth.authMode === 'stub' ? 'Stub 开发模式' : 'Sa-Token')

onMounted(async () => {
  const capabilities = await auth.fetchCapabilities()
  if (capabilities) consoleEnabled.value = capabilities.consoleEnabled
})

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  errorMsg.value = ''
  try {
    await auth.login(form.username, form.password)
    const requestedRedirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/overview'
    const redirect = requestedRedirect.startsWith('/') && !requestedRedirect.startsWith('//')
      ? requestedRedirect
      : '/overview'
    await router.replace(redirect)
  } catch (error) {
    errorMsg.value = apiErrorMessage(error)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: minmax(320px, 0.85fr) minmax(480px, 1.15fr);
  width: 100%;
  min-height: 100dvh;
  background: var(--gateway-surface);
}

.login-brand {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(40px, 7vw, 96px);
  color: #dbe5ee;
  background: var(--gateway-sidebar);
}

.product-mark {
  display: grid;
  width: 56px;
  height: 56px;
  margin-bottom: 28px;
  place-items: center;
  color: #fff;
  background: var(--gateway-primary);
  border-radius: 8px;
  font-size: 20px;
  font-weight: 750;
}

.product-context,
.eyebrow {
  margin: 0 0 8px;
  color: #89b5d4;
  font-size: 12px;
  font-weight: 700;
}

.login-brand h1 {
  max-width: 520px;
  margin: 0;
  color: #fff;
  font-size: clamp(32px, 4vw, 52px);
  line-height: 1.08;
}

.login-brand > p:last-of-type {
  margin: 12px 0 0;
  color: #b8c5d1;
  font-size: 18px;
}

.runtime-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 160px));
  gap: 12px;
  margin: 48px 0 0;
}

.runtime-facts div {
  padding-top: 12px;
  border-top: 1px solid var(--gateway-sidebar-border);
}

.runtime-facts dt {
  color: #8fa2b3;
  font-size: 12px;
}

.runtime-facts dd {
  margin: 4px 0 0;
  color: #fff;
  font-weight: 600;
}

.login-panel {
  display: grid;
  place-items: center;
  padding: 32px;
  background: var(--gateway-bg);
}

.login-form-wrap {
  width: min(100%, 420px);
  padding: 32px;
  background: var(--gateway-surface);
  border: 1px solid var(--gateway-border);
  border-radius: var(--gateway-radius);
  box-shadow: 0 12px 36px rgb(16 24 40 / 10%);
}

.login-heading {
  margin-bottom: 24px;
}

.eyebrow {
  color: var(--gateway-primary);
}

.login-heading h2 {
  margin: 0;
  font-size: 24px;
}

.login-alert {
  margin-bottom: 20px;
}

.login-button {
  width: 100%;
  min-height: 44px;
  margin-top: 4px;
}

.mode-notice {
  margin: 20px 0 0;
  color: var(--gateway-warning);
  font-size: 13px;
  text-align: center;
}

@media (max-width: 820px) {
  .login-page {
    display: block;
    overflow-y: auto;
    background: var(--gateway-bg);
  }

  .login-brand {
    min-height: 230px;
    padding: 32px 24px;
  }

  .product-mark {
    width: 44px;
    height: 44px;
    margin-bottom: 16px;
  }

  .login-brand h1 {
    font-size: 30px;
  }

  .runtime-facts {
    margin-top: 24px;
  }

  .login-panel {
    padding: 24px 16px 40px;
  }

  .login-form-wrap {
    padding: 24px;
  }
}
</style>

