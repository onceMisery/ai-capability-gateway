<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <el-container class="app-shell">
    <el-aside class="desktop-sidebar" :width="collapsed ? '76px' : '232px'">
      <div class="brand" :class="{ 'brand--collapsed': collapsed }">
        <div class="brand-mark" aria-hidden="true">AI</div>
        <div v-if="!collapsed" class="brand-copy">
          <strong>Capability Gateway</strong>
          <span>治理控制台</span>
        </div>
      </div>
      <NavigationMenu :collapsed="collapsed" />
      <div class="sidebar-footer">
        <el-tooltip :content="collapsed ? '展开导航' : '收起导航'" placement="right">
          <el-button
            class="collapse-button"
            text
            :aria-label="collapsed ? '展开导航' : '收起导航'"
            @click="collapsed = !collapsed"
          >
            <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
            <span v-if="!collapsed">收起导航</span>
          </el-button>
        </el-tooltip>
      </div>
    </el-aside>

    <el-drawer v-model="mobileNavOpen" direction="ltr" size="280px" :with-header="false" class="mobile-drawer">
      <div class="brand brand--mobile">
        <div class="brand-mark" aria-hidden="true">AI</div>
        <div class="brand-copy">
          <strong>Capability Gateway</strong>
          <span>治理控制台</span>
        </div>
      </div>
      <NavigationMenu @navigate="mobileNavOpen = false" />
    </el-drawer>

    <el-container class="workspace">
      <el-header class="topbar">
        <div class="topbar-title">
          <el-button class="mobile-menu-button" text aria-label="打开导航" @click="mobileNavOpen = true">
            <el-icon><Menu /></el-icon>
          </el-button>
          <div>
            <span class="topbar-context">AI 能力治理</span>
            <h1>{{ currentTitle }}</h1>
          </div>
        </div>
        <div class="topbar-actions">
          <el-tag size="small" effect="plain" :type="auth.authMode === 'stub' ? 'warning' : 'info'">
            {{ auth.authMode === 'stub' ? 'Stub 开发模式' : 'Sa-Token' }}
          </el-tag>
          <el-dropdown trigger="click" @command="handleUserCommand">
            <button class="user-menu" type="button" aria-label="打开用户菜单">
              <span class="user-avatar" aria-hidden="true">{{ userInitial }}</span>
              <span class="user-copy">
                <strong>{{ auth.username || '未知用户' }}</strong>
                <small>{{ auth.roles.join(' · ') || '已认证' }}</small>
              </span>
              <el-icon><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main id="main-content" class="main-content" tabindex="-1">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElIcon, ElMenu, ElMenuItem, ElMessage } from 'element-plus'
import {
  ArrowDown,
  DataLine,
  Document,
  Expand,
  Files,
  Fold,
  Grid,
  Key,
  List,
  Menu,
  Setting,
  SwitchButton
} from '@element-plus/icons-vue'

interface MenuItem {
  path: string
  title: string
  icon: Component
  requiredRole?: string
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const mobileNavOpen = ref(false)

const currentTitle = computed(() => String(route.meta.title || '治理控制台'))
const userInitial = computed(() => (auth.username || 'U').slice(0, 1).toUpperCase())

const allMenus: MenuItem[] = [
  { path: '/overview', title: '治理总览', icon: Grid, requiredRole: 'admin' },
  { path: '/capabilities', title: '能力目录', icon: List, requiredRole: 'admin' },
  { path: '/snapshots', title: '发布快照', icon: Files, requiredRole: 'admin' },
  { path: '/monitor', title: '运行监控', icon: DataLine, requiredRole: 'admin' },
  { path: '/audit', title: '审计追踪', icon: Document, requiredRole: 'admin' },
  { path: '/acl', title: '访问策略', icon: Key, requiredRole: 'admin' },
  { path: '/system', title: '系统状态', icon: Setting, requiredRole: 'admin' }
]

const visibleMenus = computed(() => {
  const wildcard = auth.permissions.includes('*')
  return allMenus.filter((item) => {
    if (item.requiredRole && !auth.roles.includes(item.requiredRole) && !wildcard) return false
    return true
  })
})

watch(
  () => ({
    loggedIn: auth.isLoggedIn,
    roles: [...auth.roles],
    permissions: [...auth.permissions]
  }),
  ({ loggedIn, roles, permissions }) => {
    if (!loggedIn || route.path === '/login' || route.path === '/403') return
    const wildcard = permissions.includes('*')
    const requiredRole = route.meta.requiredRole as string | undefined
    const allowed = !requiredRole || roles.includes(requiredRole) || wildcard
    if (!allowed) void router.replace({ path: '/403', query: { from: route.fullPath } })
  },
)

const NavigationMenu = defineComponent({
  name: 'NavigationMenu',
  props: { collapsed: { type: Boolean, default: false } },
  emits: ['navigate'],
  setup(props, { emit }) {
    return () => h(
      'nav',
      { 'aria-label': '主导航' },
      h(
        ElMenu,
        {
          defaultActive: route.path,
          collapse: props.collapsed,
          collapseTransition: false,
          router: true,
          onSelect: () => emit('navigate')
        },
        () => visibleMenus.value.map((item) => h(
          ElMenuItem,
          { key: item.path, index: item.path },
          {
            default: () => [h(ElIcon, null, () => h(item.icon)), h('span', item.title)]
          }
        ))
      )
    )
  }
})

watch(() => route.path, () => {
  mobileNavOpen.value = false
})

async function handleUserCommand(command: string) {
  if (command !== 'logout') return
  const revoked = await auth.logout()
  await router.replace('/login')
  if (!revoked) ElMessage.warning('本地会话已退出，但服务端令牌撤销失败；令牌将在过期后失效。')
}
</script>

<style scoped>
.app-shell {
  width: 100%;
  height: 100dvh;
  overflow: hidden;
}

.desktop-sidebar {
  position: relative;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: #dbe5ee;
  background: var(--gateway-sidebar);
  border-right: 1px solid var(--gateway-sidebar-border);
  transition: width 180ms ease-out;
}

.brand {
  display: flex;
  align-items: center;
  min-height: 72px;
  padding: 12px 16px;
  gap: 10px;
  border-bottom: 1px solid var(--gateway-sidebar-border);
}

.brand--collapsed {
  justify-content: center;
  padding-inline: 8px;
}

.brand--mobile {
  color: #dbe5ee;
  background: var(--gateway-sidebar);
}

:global(.mobile-drawer.el-drawer),
:global(.mobile-drawer .el-drawer__body) {
  color: #dbe5ee;
  background: var(--gateway-sidebar);
}

:global(.mobile-drawer .el-drawer__body) {
  padding: 0;
}

:global(.mobile-drawer .el-menu-item) {
  color: #b8c5d1;
}

:global(.mobile-drawer .el-menu-item:hover) {
  color: #fff;
  background: #243341;
}

:global(.mobile-drawer .el-menu-item.is-active) {
  color: #fff;
  background: var(--gateway-primary);
}

.brand-mark {
  display: grid;
  flex: 0 0 36px;
  width: 36px;
  height: 36px;
  place-items: center;
  color: #fff;
  background: var(--gateway-primary);
  border-radius: 7px;
  font-weight: 750;
}

.brand-copy {
  min-width: 0;
}

.brand-copy strong,
.brand-copy span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.brand-copy strong {
  color: #fff;
  font-size: 14px;
}

.brand-copy span {
  color: #9fb0c0;
  font-size: 12px;
}

:deep(.el-menu) {
  padding: 10px 8px;
  background: transparent;
  border-right: 0;
}

:deep(.el-menu-item) {
  min-height: 44px;
  margin-bottom: 4px;
  color: #b8c5d1;
  border-radius: 6px;
}

:deep(.el-menu-item:hover) {
  color: #fff;
  background: #243341;
}

:deep(.el-menu-item.is-active) {
  color: #fff;
  background: var(--gateway-primary);
}

.sidebar-footer {
  margin-top: auto;
  padding: 8px;
  border-top: 1px solid var(--gateway-sidebar-border);
}

.collapse-button {
  width: 100%;
  justify-content: flex-start;
  color: #b8c5d1;
}

.brand--collapsed + nav + .sidebar-footer .collapse-button,
.desktop-sidebar[style*="76px"] .collapse-button {
  justify-content: center;
}

.workspace {
  min-width: 0;
}

.topbar {
  display: flex;
  flex: 0 0 72px;
  align-items: center;
  justify-content: space-between;
  min-width: 0;
  height: 72px;
  padding: 0 24px;
  background: var(--gateway-surface);
  border-bottom: 1px solid var(--gateway-border);
}

.topbar-title,
.topbar-actions {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 12px;
}

.topbar-title h1 {
  margin: 0;
  overflow: hidden;
  font-size: 18px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar-context {
  display: block;
  color: var(--gateway-text-muted);
  font-size: 11px;
}

.mobile-menu-button {
  display: none;
  min-width: 44px;
  min-height: 44px;
}

.user-menu {
  display: flex;
  align-items: center;
  min-height: 44px;
  padding: 4px 8px;
  color: var(--gateway-text);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
}

.user-menu:hover {
  background: var(--gateway-surface-subtle);
  border-color: var(--gateway-border);
}

.user-avatar {
  display: grid;
  width: 32px;
  height: 32px;
  margin-right: 8px;
  place-items: center;
  color: #fff;
  background: var(--gateway-info);
  border-radius: 50%;
  font-weight: 700;
}

.user-copy {
  display: block;
  min-width: 92px;
  margin-right: 8px;
  text-align: left;
}

.user-copy strong,
.user-copy small {
  display: block;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-copy small {
  color: var(--gateway-text-muted);
  font-size: 11px;
}

.main-content {
  min-width: 0;
  padding: 24px;
  overflow: auto;
  background: var(--gateway-bg);
}

.main-content:focus {
  outline: none;
}

.page-fade-enter-active,
.page-fade-leave-active {
  transition: opacity 150ms ease-out;
}

.page-fade-enter-from,
.page-fade-leave-to {
  opacity: 0;
}

@media (max-width: 900px) {
  .desktop-sidebar {
    display: none;
  }

  .mobile-menu-button {
    display: inline-flex;
  }

  .topbar {
    padding: 0 16px;
  }
}

@media (max-width: 620px) {
  .topbar {
    height: 64px;
  }

  .topbar-context,
  .topbar-actions > .el-tag,
  .user-copy,
  .user-menu > .el-icon {
    display: none;
  }

  .main-content {
    padding: 16px;
  }
}
</style>
