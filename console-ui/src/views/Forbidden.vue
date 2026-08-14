<template>
  <main class="forbidden-page" aria-labelledby="forbidden-title">
    <section class="forbidden-content">
      <p class="eyebrow">403</p>
      <h1 id="forbidden-title">当前账号无权访问此页面</h1>
      <p>你的会话仍然有效，但当前角色不包含该管理操作所需的权限。</p>
      <div class="forbidden-actions">
        <el-button type="primary" :icon="Grid" @click="router.replace('/overview')">返回治理总览</el-button>
        <el-button :icon="Back" @click="router.back()">返回上一页</el-button>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { Back, Grid } from '@element-plus/icons-vue'
const router = useRouter()
</script>

<style scoped>
.forbidden-page { display: grid; min-height: 100dvh; place-items: center; padding: 24px; background: var(--gateway-bg, #f5f5f5); }
.forbidden-content { width: min(560px, 100%); padding: 40px; text-align: center; background: var(--gateway-surface, #fff); border: 1px solid var(--gateway-border, #e5e5e5); border-radius: 8px; box-shadow: 0 12px 32px rgb(0 0 0 / 10%); }
.eyebrow { margin: 0 0 8px; color: var(--gateway-danger, #111111); font-size: 14px; font-weight: 700; }
h1 { margin: 0; color: var(--gateway-text, #1f1f1f); font-size: clamp(24px, 4vw, 32px); }
p:not(.eyebrow) { margin: 16px 0 24px; color: var(--gateway-muted, #6f6f6f); line-height: 1.6; }
.forbidden-actions { display: flex; justify-content: center; flex-wrap: wrap; gap: 12px; }
</style>
