<template>
  <div class="login">
    <!-- 品牌展示区 -->
    <section class="login__brand">
      <div class="brand-decor brand-decor--1" />
      <div class="brand-decor brand-decor--2" />
      <div class="brand-inner">
        <div class="brand-logo">
          <svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true">
            <path fill="currentColor" d="M12 2 3 7v10l9 5 9-5V7l-9-5Zm0 2.3 6.5 3.6L12 11.5 5.5 7.9 12 4.3Zm-7 5.2 6 3.3v6.6l-6-3.3V9.5Zm14 0v6.6l-6 3.3v-6.6l6-3.3Z"/>
          </svg>
        </div>
        <h1 class="brand-title">AI 能力网关</h1>
        <p class="brand-tagline">统一纳管、治理与可观测企业 AI 能力</p>

        <ul class="brand-features">
          <li><el-icon><Check /></el-icon><span>能力目录与多版本快照治理</span></li>
          <li><el-icon><Check /></el-icon><span>细粒度访问控制与策略决策</span></li>
          <li><el-icon><Check /></el-icon><span>调用链审计与实时运行监控</span></li>
        </ul>

        <div class="brand-foot">© 2026 能力治理平台 · 安全合规</div>
      </div>
    </section>

    <!-- 表单区 -->
    <section class="login__form">
      <div class="form-card">
        <div class="form-head">
          <span class="eyebrow">欢迎回来</span>
          <h2>登录控制台</h2>
          <p>使用管理员凭据访问 AI 能力治理平台</p>
        </div>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="submit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="请输入用户名" :prefix-icon="User" @keyup.enter="submit" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" :prefix-icon="Lock" @keyup.enter="submit" />
          </el-form-item>

          <div v-if="errorMsg" class="inline-error" role="alert">
            <el-icon><Warning /></el-icon><span>{{ errorMsg }}</span>
          </div>

          <el-button type="primary" size="large" :loading="loading" native-type="submit" class="submit-btn">
            登 录
          </el-button>
        </el-form>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Check, Lock, User, Warning } from '@element-plus/icons-vue'
import { ElMessage, FormInstance, FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { apiErrorMessage } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const errorMsg = ref('')
const form = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const redirect = computed(() => (typeof route.query.redirect === 'string' ? route.query.redirect : '/overview'))

onMounted(() => {
  if (auth.isLoggedIn) router.replace(redirect.value)
})

async function submit() {
  if (!formRef.value) return
  errorMsg.value = ''
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    router.replace(redirect.value)
  } catch (error) {
    errorMsg.value = apiErrorMessage(error)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login {
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  min-height: 100vh;
  width: 100%;
}

/* 品牌区 */
.login__brand {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
  color: #fff;
  background: linear-gradient(150deg, #262626 0%, #171717 50%, #000000 100%);
}
.brand-decor {
  position: absolute;
  border-radius: 50%;
  filter: blur(8px);
  opacity: 0.55;
  pointer-events: none;
}
.brand-decor--1 {
  width: 360px;
  height: 360px;
  top: -120px;
  right: -90px;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.2), transparent 70%);
}
.brand-decor--2 {
  width: 300px;
  height: 300px;
  bottom: -110px;
  left: -80px;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.14), transparent 70%);
}
.brand-inner {
  position: relative;
  max-width: 420px;
}
.brand-logo {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.14);
  border: 1px solid rgba(255, 255, 255, 0.22);
  margin-bottom: 22px;
}
.brand-title {
  font-size: 34px;
  font-weight: 800;
  letter-spacing: -0.02em;
  color: #fff;
}
.brand-tagline {
  margin: 12px 0 0;
  font-size: 15px;
  color: rgba(255, 255, 255, 0.78);
}
.brand-features {
  list-style: none;
  padding: 0;
  margin: 34px 0 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.brand-features li {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14.5px;
  color: rgba(255, 255, 255, 0.9);
}
.brand-features .el-icon {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
  font-size: 14px;
  flex-shrink: 0;
}
.brand-foot {
  margin-top: 44px;
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.55);
}

/* 表单区 */
.login__form {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 32px;
  background: var(--gateway-surface);
}
.form-card {
  width: 100%;
  max-width: 380px;
}
.form-head {
  margin-bottom: 30px;
}
.form-head h2 {
  margin: 8px 0 6px;
  font-size: 25px;
  font-weight: 750;
}
.form-head p {
  margin: 0;
  color: var(--gateway-text-muted);
  font-size: 13.5px;
}
.form-card :deep(.el-form-item__label) {
  font-weight: 600;
  color: var(--gateway-text-secondary);
  padding-bottom: 4px;
}
.submit-btn {
  width: 100%;
  margin-top: 6px;
  height: 46px;
  font-size: 15px;
  letter-spacing: 0.1em;
}

/* 响应式 */
@media (max-width: 900px) {
  .login {
    grid-template-columns: 1fr;
  }
  .login__brand {
    display: none;
  }
  .login__form {
    padding: 32px 20px;
  }
}
</style>
