import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/Login.vue'),
      meta: { public: true, title: '登录' }
    },
    {
      path: '/403',
      name: 'Forbidden',
      component: () => import('@/views/Forbidden.vue'),
      meta: { title: '无权限访问' }
    },
    {
      path: '/',
      component: () => import('@/layout/MainLayout.vue'),
      redirect: '/overview',
      children: [
        {
          path: 'overview',
          name: 'Overview',
          component: () => import('@/views/GovernanceOverview.vue'),
          meta: { title: '治理总览', requiredRole: 'admin' }
        },
        {
          path: 'capabilities',
          name: 'Capabilities',
          component: () => import('@/views/CapabilityList.vue'),
          meta: { title: '能力目录', requiredRole: 'admin' }
        },
        {
          path: 'snapshots',
          name: 'Snapshots',
          component: () => import('@/views/SnapshotList.vue'),
          meta: { title: '发布快照', requiredRole: 'admin' }
        },
        {
          path: 'monitor',
          name: 'Monitor',
          component: () => import('@/views/MonitorDashboard.vue'),
          meta: { title: '运行监控', requiredRole: 'admin' }
        },
        {
          path: 'debug',
          name: 'Debug',
          component: () => import('@/views/CapabilityDebug.vue'),
          meta: { title: '能力调试', requiredRole: 'admin' }
        },
        {
          path: 'audit',
          name: 'Audit',
          component: () => import('@/views/AuditList.vue'),
          meta: { title: '审计追踪', requiredRole: 'admin' }
        },
        {
          path: 'acl',
          name: 'ACL',
          component: () => import('@/views/AclManagement.vue'),
          meta: { title: '访问策略', requiredRole: 'admin' }
        },
        {
          path: 'system',
          name: 'System',
          component: () => import('@/views/SystemConfig.vue'),
          meta: { title: '系统状态', requiredRole: 'admin' }
        }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/overview' }
  ]
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (!auth.restored) {
    await auth.restoreSession()
  }

  if (to.meta.public) {
    if (to.path === '/login' && auth.isLoggedIn) return '/overview'
    return true
  }

  if (!auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  const requiredRole = to.meta.requiredRole as string | undefined
  const hasWildcard = auth.permissions.includes('*')
  if (requiredRole && !auth.roles.includes(requiredRole) && !hasWildcard) {
    return { path: '/403', query: { from: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title || '治理控制台')} | AI Capability Gateway`
  requestAnimationFrame(() => document.querySelector<HTMLElement>('#main-content')?.focus())
})

export default router
