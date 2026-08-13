<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h2 class="page-title">访问策略</h2>
        <p class="page-subtitle">维护角色、权限词和能力级 ACL；运行时授权仍由网关在执行前再次校验。</p>
      </div>
      <div class="page-actions"><el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button></div>
    </header>

    <section class="stat-grid strategy-stats" aria-label="策略概览">
      <article class="stat-card"><div class="stat-label"><el-icon><UserFilled /></el-icon> 角色</div><div class="stat-value data-number">{{ roles.length }}</div><div class="stat-meta">可用于 ACL 授权</div></article>
      <article class="stat-card"><div class="stat-label"><el-icon><Key /></el-icon> 权限词</div><div class="stat-value data-number">{{ permissions.length }}</div><div class="stat-meta">三段式权限契约</div></article>
      <article class="stat-card"><div class="stat-label"><el-icon><List /></el-icon> ACL 条目</div><div class="stat-value data-number">{{ aclEntries.length }}</div><div class="stat-meta">能力级访问边界</div></article>
      <article class="stat-card"><div class="stat-label"><el-icon><Lock /></el-icon> 当前主体</div><div class="stat-value strategy-user">{{ auth.username || '-' }}</div><div class="stat-meta">本次修改将记录为该主体</div></article>
    </section>

    <section class="surface strategy-surface">
      <el-tabs v-model="activeTab" class="strategy-tabs">
        <el-tab-pane name="roles">
          <template #label><span><el-icon><UserFilled /></el-icon> 角色</span></template>
          <div class="tab-toolbar"><el-input v-model="roleSearch" clearable :prefix-icon="Search" placeholder="搜索角色或描述" /><el-button type="primary" :icon="Plus" @click="openRoleDialog()">新增角色</el-button></div>
          <div class="table-wrap"><el-table v-if="filteredRoles.length" :data="filteredRoles" v-loading="loading" stripe style="min-width: 780px">
            <el-table-column prop="name" label="角色" width="190"><template #default="{ row }"><strong>{{ row.name }}</strong></template></el-table-column>
            <el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip />
            <el-table-column label="权限" min-width="300"><template #default="{ row }"><div class="tag-list"><el-tag v-for="permission in row.permissions" :key="permission" size="small" effect="plain">{{ permission }}</el-tag><span v-if="!row.permissions.length" class="muted">未分配</span></div></template></el-table-column>
            <el-table-column label="操作" width="170"><template #default="{ row }"><el-button text type="primary" @click="openRoleDialog(row)">编辑</el-button><el-button text type="danger" @click="deleteRole(row)">删除</el-button></template></el-table-column>
          </el-table><div v-else class="empty-state"><div><strong>暂无角色</strong><span>创建角色后再为能力配置访问边界。</span></div></div></div>
        </el-tab-pane>

        <el-tab-pane name="acl">
          <template #label><span><el-icon><Lock /></el-icon> 能力 ACL</span></template>
          <div class="tab-toolbar"><el-input v-model="aclSearch" clearable :prefix-icon="Search" placeholder="搜索能力或角色" /><el-button type="primary" :icon="Plus" @click="openAclDialog()">新增 ACL</el-button></div>
          <div class="table-wrap"><el-table v-if="filteredAcl.length" :data="filteredAcl" v-loading="loading" stripe style="min-width: 900px">
            <el-table-column label="能力" min-width="250"><template #default="{ row }"><span class="mono">{{ row.capabilityId }}</span></template></el-table-column>
            <el-table-column label="版本" width="100"><template #default="{ row }"><span class="mono">v{{ row.capabilityVersion }}</span></template></el-table-column>
            <el-table-column label="允许角色" min-width="260"><template #default="{ row }"><div class="tag-list"><el-tag v-for="role in row.allowedRoles" :key="role" size="small" type="success" effect="plain">{{ role }}</el-tag><el-tag v-if="!row.allowedRoles.length" size="small" type="warning" effect="plain">不限制角色</el-tag></div></template></el-table-column>
            <el-table-column label="Manifest 权限" min-width="220"><template #default="{ row }"><div class="tag-list"><el-tag v-for="permission in row.requiredPermissions" :key="permission" size="small" effect="plain">{{ permission }}</el-tag><span v-if="!row.requiredPermissions.length" class="muted">无额外权限</span></div></template></el-table-column>
            <el-table-column label="更新" width="190"><template #default="{ row }"><span>{{ formatDateTime(row.updatedAt) }}</span><small class="muted">{{ row.updatedBy }}</small></template></el-table-column>
            <el-table-column label="操作" width="150"><template #default="{ row }"><el-button text type="primary" @click="openAclDialog(row)">编辑</el-button><el-button text type="danger" @click="deleteAcl(row)">删除</el-button></template></el-table-column>
          </el-table><div v-else class="empty-state"><div><strong>暂无 ACL 条目</strong><span>当前初始策略会允许所有已认证调用者，请尽快配置能力级边界。</span></div></div></div>
        </el-tab-pane>

        <el-tab-pane name="permissions">
          <template #label><span><el-icon><Key /></el-icon> 权限词</span></template>
          <div class="tab-toolbar"><el-input v-model="permissionSearch" clearable :prefix-icon="Search" placeholder="搜索权限名或描述" /><el-button type="primary" :icon="Plus" @click="openPermissionDialog">新增权限</el-button></div>
          <div class="table-wrap"><el-table v-if="filteredPermissions.length" :data="filteredPermissions" v-loading="loading" stripe style="min-width: 720px">
            <el-table-column prop="name" label="权限名" width="280"><template #default="{ row }"><span class="mono">{{ row.name }}</span></template></el-table-column>
            <el-table-column prop="description" label="描述" min-width="280" show-overflow-tooltip />
            <el-table-column label="创建时间" width="190"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
            <el-table-column label="操作" width="100"><template #default="{ row }"><el-button text type="danger" @click="deletePermission(row)">删除</el-button></template></el-table-column>
          </el-table><div v-else class="empty-state"><div><strong>暂无权限词</strong><span>权限名必须符合 domain:resource:action 约定。</span></div></div></div>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog v-model="roleDialogOpen" :title="editingRole ? '编辑角色' : '新增角色'" width="min(560px, calc(100vw - 24px))">
      <el-form ref="roleFormRef" :model="roleForm" :rules="roleRules" label-position="top">
        <el-form-item label="角色名" prop="name"><el-input v-model.trim="roleForm.name" :disabled="!!editingRole" autocomplete="off" placeholder="例如 order-analyst" /></el-form-item>
        <el-form-item label="描述" prop="description"><el-input v-model.trim="roleForm.description" maxlength="256" show-word-limit placeholder="说明该角色负责的工作范围" /></el-form-item>
        <el-form-item label="权限" prop="permissions"><el-select v-model="roleForm.permissions" multiple filterable collapse-tags :max-collapse-tags="3" style="width: 100%" placeholder="选择已有权限词"><el-option v-for="permission in permissions" :key="permission.name" :label="permission.name" :value="permission.name" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="roleDialogOpen = false">取消</el-button><el-button type="primary" :loading="dialogLoading" @click="saveRole">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="aclDialogOpen" title="配置能力 ACL" width="min(560px, calc(100vw - 24px))">
      <el-form ref="aclFormRef" :model="aclForm" :rules="aclRules" label-position="top">
        <el-form-item label="能力" prop="capabilityKey"><el-select v-model="aclForm.capabilityKey" filterable :disabled="!!editingAcl" style="width: 100%" placeholder="选择能力版本"><el-option v-for="capability in capabilities" :key="`${capability.capabilityId}@${capability.version}`" :label="`${capability.displayName} · ${capability.capabilityId} v${capability.version}`" :value="`${capability.capabilityId}@${capability.version}`" /></el-select></el-form-item>
        <el-form-item label="允许角色" prop="allowedRoles"><el-select v-model="aclForm.allowedRoles" multiple filterable collapse-tags style="width: 100%" placeholder="选择允许调用的角色"><el-option v-for="role in roles" :key="role.name" :label="role.name" :value="role.name" /></el-select><div class="form-helper">后端的空角色列表表示不限制角色；控制台要求至少选择一个角色，避免意外扩大访问面。</div></el-form-item>
      </el-form>
      <template #footer><el-button @click="aclDialogOpen = false">取消</el-button><el-button type="primary" :loading="dialogLoading" @click="saveAcl">保存 ACL</el-button></template>
    </el-dialog>

    <el-dialog v-model="permissionDialogOpen" title="新增权限词" width="min(500px, calc(100vw - 24px))">
      <el-form ref="permissionFormRef" :model="permissionForm" :rules="permissionRules" label-position="top">
        <el-form-item label="权限名" prop="name"><el-input v-model.trim="permissionForm.name" autocomplete="off" placeholder="domain:resource:action" /></el-form-item>
        <el-form-item label="描述" prop="description"><el-input v-model.trim="permissionForm.description" maxlength="256" placeholder="说明允许的业务动作" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="permissionDialogOpen = false">取消</el-button><el-button type="primary" :loading="dialogLoading" @click="savePermission">创建权限</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Key, List, Lock, Plus, Refresh, Search, UserFilled } from '@element-plus/icons-vue'
import { gatewayApi } from '@/api/gateway'
import { useAuthStore } from '@/stores/auth'
import { apiErrorMessage, formatDateTime } from '@/utils/format'
import type { CapabilityAclEntry, CapabilitySummary, Permission, Role } from '@/types/gateway'

const auth = useAuthStore()
const loading = ref(false)
const dialogLoading = ref(false)
const activeTab = ref('roles')
const roles = ref<Role[]>([])
const permissions = ref<Permission[]>([])
const aclEntries = ref<CapabilityAclEntry[]>([])
const capabilities = ref<CapabilitySummary[]>([])
const roleSearch = ref('')
const aclSearch = ref('')
const permissionSearch = ref('')
const roleDialogOpen = ref(false)
const aclDialogOpen = ref(false)
const permissionDialogOpen = ref(false)
const editingRole = ref<Role>()
const editingAcl = ref<CapabilityAclEntry>()
const roleFormRef = ref<FormInstance>()
const aclFormRef = ref<FormInstance>()
const permissionFormRef = ref<FormInstance>()
const roleForm = reactive({ name: '', description: '', permissions: [] as string[] })
const aclForm = reactive({ capabilityKey: '', allowedRoles: [] as string[] })
const permissionForm = reactive({ name: '', description: '' })
const roleRules: FormRules = { name: [{ required: true, message: '请输入角色名', trigger: 'blur' }], description: [{ required: true, message: '请输入描述', trigger: 'blur' }], permissions: [{ required: true, type: 'array', min: 1, message: '至少选择一个权限词', trigger: 'change' }] }
const aclRules: FormRules = { capabilityKey: [{ required: true, message: '请选择能力版本', trigger: 'change' }], allowedRoles: [{ required: true, type: 'array', min: 1, message: '至少选择一个允许角色', trigger: 'change' }] }
const permissionRules: FormRules = { name: [{ required: true, pattern: /^[a-z][a-z0-9]*(:[a-z][a-z0-9]*){2}$/, message: '使用 domain:resource:action 格式', trigger: 'blur' }], description: [{ required: true, message: '请输入描述', trigger: 'blur' }] }

const filteredRoles = computed(() => filterBy(roles.value, roleSearch.value, (row) => [row.name, row.description, ...row.permissions]))
const filteredAcl = computed(() => filterBy(aclEntries.value, aclSearch.value, (row) => [row.capabilityId, row.capabilityVersion, ...row.allowedRoles]))
const filteredPermissions = computed(() => filterBy(permissions.value, permissionSearch.value, (row) => [row.name, row.description]))

onMounted(loadAll)

function filterBy<T>(rows: T[], query: string, fields: (row: T) => string[]) {
  const needle = query.trim().toLowerCase()
  return needle ? rows.filter((row) => fields(row).some((value) => value.toLowerCase().includes(needle))) : rows
}

async function loadAll() {
  loading.value = true
  const results = await Promise.allSettled([gatewayApi.roles(), gatewayApi.permissions(), gatewayApi.aclEntries(), gatewayApi.capabilities()])
  if (results[0].status === 'fulfilled') roles.value = results[0].value
  if (results[1].status === 'fulfilled') permissions.value = results[1].value
  if (results[2].status === 'fulfilled') aclEntries.value = results[2].value
  if (results[3].status === 'fulfilled') capabilities.value = results[3].value
  const firstError = results.find((item) => item.status === 'rejected')
  if (firstError?.status === 'rejected') ElMessage.error(apiErrorMessage(firstError.reason))
  loading.value = false
}

function openRoleDialog(role?: Role) {
  editingRole.value = role
  roleForm.name = role?.name || ''
  roleForm.description = role?.description || ''
  roleForm.permissions = [...(role?.permissions || [])]
  roleDialogOpen.value = true
}

async function saveRole() {
  if (!await roleFormRef.value?.validate().catch(() => false)) return
  dialogLoading.value = true
  try { await gatewayApi.saveRole(roleForm, !!editingRole.value); ElMessage.success(editingRole.value ? '角色已更新' : '角色已创建'); roleDialogOpen.value = false; await loadAll() }
  catch (error) { ElMessage.error(apiErrorMessage(error)) }
  finally { dialogLoading.value = false }
}

async function deleteRole(role: Role) {
  try { await ElMessageBox.confirm(`确认删除角色“${role.name}”？如果仍被 ACL 引用，后端会拒绝删除。`, '删除角色', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }) }
  catch { return }
  try { await gatewayApi.deleteRole(role.name); ElMessage.success('角色已删除'); await loadAll() }
  catch (error) { ElMessage.error(apiErrorMessage(error)) }
}

function openAclDialog(entry?: CapabilityAclEntry) {
  editingAcl.value = entry
  aclForm.capabilityKey = entry ? `${entry.capabilityId}@${entry.capabilityVersion}` : ''
  aclForm.allowedRoles = [...(entry?.allowedRoles || [])]
  aclDialogOpen.value = true
}

async function saveAcl() {
  if (!await aclFormRef.value?.validate().catch(() => false)) return
  const updatedBy = auth.username.trim()
  if (!updatedBy) {
    ElMessage.error('当前会话缺少主体标识，请重新登录后再保存 ACL')
    return
  }
  const separator = aclForm.capabilityKey.lastIndexOf('@')
  const capabilityId = aclForm.capabilityKey.slice(0, separator)
  const version = aclForm.capabilityKey.slice(separator + 1)
  dialogLoading.value = true
  try { await gatewayApi.saveAcl({ capabilityId, capabilityVersion: version, allowedRoles: aclForm.allowedRoles }, updatedBy); ElMessage.success('ACL 已保存'); aclDialogOpen.value = false; await loadAll() }
  catch (error) { ElMessage.error(apiErrorMessage(error)) }
  finally { dialogLoading.value = false }
}

async function deleteAcl(entry: CapabilityAclEntry) {
  try { await ElMessageBox.confirm(`确认删除 ${entry.capabilityId} v${entry.capabilityVersion} 的 ACL？删除后请确认默认授权策略。`, '删除 ACL', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }) }
  catch { return }
  try { await gatewayApi.deleteAcl(entry.capabilityId, entry.capabilityVersion); ElMessage.success('ACL 已删除'); await loadAll() }
  catch (error) { ElMessage.error(apiErrorMessage(error)) }
}

function openPermissionDialog() {
  permissionForm.name = ''
  permissionForm.description = ''
  permissionDialogOpen.value = true
}

async function savePermission() {
  if (!await permissionFormRef.value?.validate().catch(() => false)) return
  dialogLoading.value = true
  try { await gatewayApi.savePermission(permissionForm); ElMessage.success('权限已创建'); permissionDialogOpen.value = false; await loadAll() }
  catch (error) { ElMessage.error(apiErrorMessage(error)) }
  finally { dialogLoading.value = false }
}

async function deletePermission(permission: Permission) {
  try { await ElMessageBox.confirm(`确认删除权限“${permission.name}”？若仍被角色引用，后端会拒绝删除。`, '删除权限', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }) }
  catch { return }
  try { await gatewayApi.deletePermission(permission.name); ElMessage.success('权限已删除'); await loadAll() }
  catch (error) { ElMessage.error(apiErrorMessage(error)) }
}
</script>

<style scoped>
.strategy-user {
  overflow: hidden;
  font-size: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.strategy-surface {
  margin-top: 16px;
}

.strategy-tabs {
  padding: 0 16px 16px;
}

.strategy-tabs :deep(.el-tabs__item span) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.tab-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin: 12px 0 16px;
}

.tab-toolbar > .el-input {
  max-width: 360px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.form-helper {
  margin-top: 6px;
  color: var(--gateway-text-muted);
  font-size: 12px;
}

small.muted {
  display: block;
  margin-top: 2px;
}

@media (max-width: 620px) {
  .tab-toolbar > .el-input,
  .tab-toolbar > .el-button {
    width: 100%;
    max-width: none;
  }
}
</style>
