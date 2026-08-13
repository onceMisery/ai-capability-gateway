<template>
  <div class="layout" :class="{ 'is-mobile-open': mobileOpen }">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="sidebar__brand">
        <span class="brand-mark">
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
            <path fill="currentColor" d="M12 2 3 7v10l9 5 9-5V7l-9-5Zm0 2.3 6.5 3.6L12 11.5 5.5 7.9 12 4.3Zm-7 5.2 6 3.3v6.6l-6-3.3V9.5Zm14 0v6.6l-6 3.3v-6.6l6-3.3Z"/>
          </svg>
        </span>
        <span v-if="!collapsed" class="brand-text">
          <strong>AI 能力网关</strong>
          <small>能力治理控制台</small>
        </span>
      </div>

      <el-menu
        class="sidebar__menu"
        :default-active="route.path"
        :collapse="collapsed && !isMobile"
        :collapse-transition="false"
        background-color="transparent"
        text-color="rgba(255,255,255,0.72)"
        active-text-color="#ffffff"
        router
      >
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>

      <button class="sidebar__toggle" type="button" :title="collapsed ? '展开' : '收起'" @click="collapsed = !collapsed">
        <el-icon><ArrowLeft v-if="!collapsed" /><ArrowRight v-else /></el-icon>
      </button>
    </aside>

    <!-- 移动端遮罩 -->
    <div class="sidebar-backdrop" @click="mobileOpen = false" />

    <!-- 主区域 -->
    <div class="main">
      <header class="topbar">
        <div class="topbar__left">
          <button class="icon-btn topbar__menu" type="button" aria-label="菜单" @click="mobileOpen = !mobileOpen">
            <el-icon><Menu /></el-icon>
          </button>
          <div class="topbar__title">
            <span class="eyebrow">控制台</span>
            <h1>{{ currentTitle }}</h1>
          </div>
        </div>

        <div class="topbar__right">
          <el-button text :icon="Refresh" @click="reloadCurrent">刷新</el-button>
          <el-dropdown trigger="click">
            <button class="user-chip" type="button">
              <span class="user-chip__avatar">{{ initial }}</span>
              <span class="user-chip__meta">
                <strong>{{ username }}</strong>
                <small>管理员</small>
              </span>
              <el-icon class="user-chip__caret"><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :icon="SwitchButton" @click="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown, ArrowLeft, ArrowRight, Cpu, DataLine, Document,
  Lock, Menu, Monitor, Refresh, Setting, SwitchButton, Warning
} from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()

const collapsed = ref(false)
const mobileOpen = ref(false)
const isMobile = ref(window.innerWidth <= 768)

const menuItems = [
  { path: '/overview', title: '治理总览', icon: DataLine },
  { path: '/capabilities', title: '能力目录', icon: Cpu },
  { path: '/snapshots', title: '快照版本', icon: Document },
  { path: '/monitor', title: '运行监控', icon: Monitor },
  { path: '/audit', title: '审计追踪', icon: Warning },
  { path: '/acl', title: '访问控制', icon: Lock },
  { path: '/config', title: '系统配置', icon: Setting }
]

const currentTitle = computed(() => menuItems.find(item => route.path.startsWith(item.path))?.title || '控制台')
const username = ref(localStorage.getItem('gateway_username') || 'admin')
const initial = computed(() => (username.value || 'A').charAt(0).toUpperCase())

function reloadCurrent() {
  window.location.reload()
}

function logout() {
  ElMessageBox.confirm('确定要退出当前登录吗？', '退出登录', {
    confirmButtonText: '退出',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    localStorage.removeItem('gateway_token')
    localStorage.removeItem('gateway_username')
    router.replace('/login')
  }).catch(() => {})
}
</script>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
  width: 100%;
  overflow: hidden;
}

/* ---------- 侧边栏 ---------- */
.sidebar {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 244px;
  flex-shrink: 0;
  padding: 18px 14px;
  color: #fff;
  background: linear-gradient(165deg, #1e1b4b 0%, #312e81 52%, #4c1d95 100%);
  transition: width var(--gateway-transition);
  z-index: 30;
}
.sidebar::before {
  content: "";
  position: absolute;
  inset: 0;
  background: radial-gradient(420px 220px at 30% -10%, rgba(139, 92, 246, 0.45), transparent 60%);
  pointer-events: none;
}
.sidebar.collapsed {
  width: 84px;
}

.sidebar__brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px 20px;
  position: relative;
}
.brand-mark {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  color: #fff;
  background: var(--gateway-gradient-brand);
  box-shadow: 0 8px 20px rgba(99, 102, 241, 0.5);
  flex-shrink: 0;
}
.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  white-space: nowrap;
}
.brand-text strong {
  font-size: 15px;
  font-weight: 700;
  color: #fff;
}
.brand-text small {
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.6);
}

.sidebar__menu {
  flex: 1;
  border-right: none !important;
  overflow-y: auto;
  overflow-x: hidden;
}
.sidebar__menu:not(.el-menu--collapse) {
  width: 100%;
}
.sidebar .el-menu-item {
  height: 48px;
  margin: 5px 4px;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 500;
  transition: all var(--gateway-transition);
}
.sidebar .el-menu-item .el-icon {
  font-size: 18px;
}
.sidebar .el-menu-item:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}
.sidebar .el-menu-item.is-active {
  background: var(--gateway-gradient-brand);
  color: #fff;
  font-weight: 650;
  box-shadow: 0 10px 24px rgba(99, 102, 241, 0.5);
}
.sidebar.collapsed .el-menu-item {
  margin: 5px auto;
  width: 52px;
}

.sidebar__toggle {
  margin-top: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  height: 40px;
  border: none;
  border-radius: 10px;
  color: rgba(255, 255, 255, 0.75);
  background: rgba(255, 255, 255, 0.08);
  cursor: pointer;
  transition: all var(--gateway-transition);
}
.sidebar__toggle:hover {
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
}
.sidebar.collapsed .sidebar__toggle {
  padding: 0;
}

/* ---------- 主区域 ---------- */
.main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  height: 100vh;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 64px;
  flex-shrink: 0;
  padding: 0 22px;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: saturate(180%) blur(12px);
  border-bottom: 1px solid var(--gateway-border);
  z-index: 20;
}
.topbar__left {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.topbar__title {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}
.topbar__title .eyebrow {
  font-size: 11px;
}
.topbar__title h1 {
  font-size: 17px;
  font-weight: 700;
  color: var(--gateway-text);
}
.topbar__right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.icon-btn {
  display: none;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--gateway-text-secondary);
  font-size: 20px;
  cursor: pointer;
  transition: all var(--gateway-transition);
}
.icon-btn:hover {
  background: var(--gateway-surface-subtle);
  color: var(--gateway-primary);
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 10px 5px 5px;
  border: 1px solid transparent;
  border-radius: var(--gateway-radius-pill);
  background: transparent;
  cursor: pointer;
  transition: all var(--gateway-transition);
}
.user-chip:hover {
  background: var(--gateway-surface-subtle);
  border-color: var(--gateway-border);
}
.user-chip__avatar {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--gateway-gradient-brand);
  color: #fff;
  font-weight: 700;
  font-size: 14px;
}
.user-chip__meta {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
  text-align: left;
}
.user-chip__meta strong {
  font-size: 13px;
  color: var(--gateway-text);
}
.user-chip__meta small {
  font-size: 11px;
  color: var(--gateway-text-muted);
}
.user-chip__caret {
  color: var(--gateway-text-muted);
  font-size: 13px;
}

.content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}

/* ---------- 移动端 ---------- */
.sidebar-backdrop {
  display: none;
}

@media (max-width: 768px) {
  .icon-btn.topbar__menu {
    display: flex;
  }
  .sidebar {
    position: fixed;
    top: 0;
    left: 0;
    height: 100vh;
    width: 256px;
    transform: translateX(-100%);
    transition: transform var(--gateway-transition);
    box-shadow: var(--gateway-shadow-lg);
  }
  .layout.is-mobile-open .sidebar {
    transform: translateX(0);
  }
  .layout.is-mobile-open .sidebar-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(15, 15, 35, 0.45);
    backdrop-filter: blur(2px);
    z-index: 25;
  }
  .topbar {
    padding: 0 14px;
  }
  .topbar__title h1 {
    font-size: 15px;
  }
  .user-chip__meta {
    display: none;
  }
  .sidebar .sidebar__toggle {
    display: none;
  }
}
</style>
