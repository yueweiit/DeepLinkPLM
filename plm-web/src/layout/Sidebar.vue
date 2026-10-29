<script setup lang="ts">
import { computed, onMounted, ref, resolveDynamicComponent, watch } from 'vue'
import { ArrowDown, ArrowLeftBold, ArrowRightBold, ArrowUp } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'

import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { t } from '@/locales'
import brandLogo from '@/assets/brand/deeplinkplm-logo.png'
import type { MenuGroup, MenuItem } from '@/types/common'
import { RESEARCH_BUSINESS } from '@/utils/researchNavigation'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()
const collapsed=computed(()=>appStore.sidebarCollapsed&&!appStore.mobileNavigationOpen)

const menuGroups: MenuGroup[] = [
  {
    title: '工作台',
    items: [
      { path: '/dashboard', title: '首页总览', icon: 'Odometer', permission: 'dashboard:view' },
      { path: '/my-tasks', title: '我的待办', icon: 'Checked', permission: 'pilot:tasks' },
      { path: '/samples', title: '样品管理', icon: 'Box', permission: 'partner:view' },
      { path: '/projects', title: '项目中心', icon: 'Management', permission: 'project:view' }
    ]
  },
  {
    title: '型号研发',
    items: [
      { path: '/market-watch', title: '开模申请', icon: 'Monitor', permission: 'collaboration:view' },
      { path: '/mold-applications', title: '开模申请进度', icon: 'DocumentChecked', permission: 'collaboration:view' },
      { path: '/mold-schedules', title: '开模时间管控', icon: 'Timer', permission: 'schedule:read' },
      { path: '/model-samples', title: '样品管理', icon: 'Box', permission: 'partner:view' },
      { path: '/production-totals', title: '累计产量', icon: 'Histogram', permission: 'collaboration:view' }
    ]
  },
  { title: '新品研发', items: [{path:'/product-research',title:'新品项目',icon:'EditPen',permission:'project:view'}, {path:'/research-samples',title:'样品管理',icon:'Box',permission:'partner:view'}, ...RESEARCH_BUSINESS] },
  { title: '合作产品', items: [{ path: '/partner-products', title: '供应商产品目录', icon: 'Goods', permission: 'partner:view' }, { path: '/partner-selections', title: '选品目录', icon: 'Grid', permission: 'partner:view' }, {path:'/partner-samples',title:'样品管理',icon:'Box',permission:'partner:view'}, { path: '/partner-listings', title: '上架产品目录', icon: 'Shop', permission: 'partner:view' }, { path: '/partner-fulfilments', title: '委外与交付', icon: 'Van', permission: 'partner:view' }] },
  { title: '工程资料', items: [
    { path: '/files', title: '图纸与 3D 文件', icon: 'FolderOpened', permission: 'file:read' },
    { path: '/bom', title: 'BOM 管理', icon: 'List', permission: 'product:view' },
    { path: '/processes', title: '工艺管理', icon: 'Connection', permission: 'process:view' }
  ] },
  { title: '费用管理', items: [
    { path: '/development-budgets', title: '模具成本预算', icon: 'Coin', permission: 'finance:read' },
    { path: '/development-expenses', title: '开发费用归集', icon: 'Wallet', permission: 'finance:read' }
  ] },
  { title: '基础资料', items: [
    { path: '/projects?tab=archived&archiveView=product', title: '产品平台与型号', icon: 'Grid', permission: 'product:view' },
    { path: '/molds', title: '模具管理', icon: 'Box', permission: 'collaboration:view' },
    { path: '/phone-model-codes', title: '手机编码', icon: 'Cellphone', permission: 'collaboration:view' },
    { path: '/inventories', title: '物料库存', icon: 'Box', permission: 'inventory:view' },
    { path: '/suppliers', title: '供应商管理', icon: 'Van', permission: 'supplier:view' },
    { path: '/customers', title: '客户管理', icon: 'User', permission: 'customer:view' },
    { path: '/code-center', title: '颜色编码', icon: 'CollectionTag', permission: 'product:view' }
  ] },
  {
    title: '报表中心',
    items: [
      { path: '/reports?view=schedule', title: '项目周期', icon: 'DataAnalysis', permission: 'report:view' },
      { path: '/reports?view=finance', title: '预算与实际', icon: 'Coin', permission: 'finance:read' },
      { path: '/reports?view=pilot', title: '试点评估', icon: 'TrendCharts', permission: 'report:view' }
    ]
  },
  {
    title: '系统管理',
    items: [
      { path: '/system/users', title: '账号与权限', icon: 'Avatar', permission: 'pilot:admin' },
      { path: '/system/operation-log', title: '系统日志', icon: 'Tickets', permission: 'admin:log' },
      { path: '/approval-tasks', title: '流程配置', icon: 'Checked', permission: 'approval:view' },
      { path: '/system/assignments', title: '协作职责', icon: 'User', permission: 'pilot:admin' },
      { path: '/system/integrations', title: '系统对接', icon: 'Connection', permission: 'pilot:admin' },
      { path: '/system/trials', title: '试点范围配置', icon: 'SetUp', permission: 'pilot:admin' },
      { path: '/system/import', title: '数据导入导出', icon: 'Upload', permission: 'admin:import' }
    ]
  }
]
const external = computed(() => ['supplier','mexico'].includes(userStore.profile?.roleCode || ''))

const visibleMenus = computed(() =>
  menuGroups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => userStore.hasPermission(item.permission)
        && (item.path !== '/partner-selections' || !external.value)
        && (!RESEARCH_BUSINESS.some(entry=>entry.path===item.path) || userStore.hasPermission('project:view'))
        && (item.path !== '/market-watch' || appStore.phoneWatchEnabled || appStore.moldApplicationEnabled)
        && (item.path !== '/mold-applications' || appStore.moldApplicationEnabled)
        && (item.path !== '/my-tasks' || external.value || userStore.hasPermission('schedule:read') || userStore.hasPermission('partner:view')))
        .map(item => item.path === '/my-tasks' && external.value ? { ...item, path: '/pilot/tasks' } : item)
    }))
    .filter((group) => group.items.length)
)

onMounted(() => { if (userStore.hasPermission('collaboration:view')) void appStore.loadFeatureConfig() })
watch(() => userStore.token, (token) => { if (token && userStore.hasPermission('collaboration:view')) void appStore.loadFeatureConfig(true) })

function normalizePath(path: string) {
  return path.replace(/\/+$/, '')
}

function isActive(path: string) {
  if (route.path.startsWith('/partner-operations/listing/')) return path === '/partner-listings'
  if (path === '/partner-fulfilments' && (route.path.startsWith('/partner-operations/') || route.path === '/partner-sales')) return true
  const target = router.resolve(path)
  if (normalizePath(target.path) !== normalizePath(route.path)) return false
  if (target.path === '/product-research' && route.query.flow === 'lightweight') return false
  if (target.path === '/product-research') return true
  if (Object.keys(target.query).length) return Object.entries(target.query).every(([key, value]) => (route.path === '/reports' && key === 'view' ? route.query[key] || 'schedule' : route.query[key]) === value)
  if (target.path === '/projects') return !['product', 'sku'].includes(String(route.query.archiveView || ''))
  return true
}

function groupHasActiveItem(group: MenuGroup) {
  return group.items.some((item) => isActive(item.path))
}

const activeGroupTitle = ref('')

function syncActiveGroup() {
  const matched = visibleMenus.value.find((group) => groupHasActiveItem(group))
  activeGroupTitle.value = matched?.title || visibleMenus.value[0]?.title || ''

}

watch(
  () => [route.fullPath, visibleMenus.value.map((group) => group.title).join('|')],
  () => {
    syncActiveGroup()
  },
  { immediate: true }
)

function isGroupOpen(group: MenuGroup) {
  if (collapsed.value) return false
  return activeGroupTitle.value === group.title
}

function toggleGroup(title: string) {
  if (collapsed.value) {
    appStore.setSidebarCollapsed(false)
    activeGroupTitle.value = title
    return
  }

  activeGroupTitle.value = activeGroupTitle.value === title ? '' : title
}

function handleGroupClick(group: MenuGroup) {
  toggleGroup(group.title)
}

async function navigate(path: string, groupTitle: string) {
  activeGroupTitle.value = groupTitle
  const target=router.resolve(path)
  const withinResearch = route.path === '/product-research' || RESEARCH_BUSINESS.some(item => item.path === route.path)
  const isStage = target.path === '/product-research'
  const isBusiness = RESEARCH_BUSINESS.some(item => item.path === target.path)
  const destination = withinResearch && route.query.flow !== 'lightweight' && route.query.productId && (isStage || isBusiness)
    ? { path: target.path, query: { ...target.query, productId: route.query.productId,
        ...(route.query.step ? {step:route.query.step}:route.query.stage?{stage:route.query.stage}:{}) } } : path
  const failed = await router.push(destination)
  appStore.closeMobileNavigation()
  if (failed) syncActiveGroup()
}

function handleMenuItemClick(item: MenuItem, groupTitle: string) {
  navigate(item.path, groupTitle)
}
</script>

<template>
  <aside class="sidebar" :class="{ 'sidebar--collapsed': collapsed }">
    <div class="sidebar__brand">
      <img class="sidebar__logo" :src="brandLogo" :alt="t('common.appName')" width="40" height="40" />
      <div v-if="!collapsed" class="sidebar__title">
        <strong>{{ t('common.appName') }}</strong>
        <span>产品研发协同</span>
      </div>
    </div>

    <div class="sidebar__menus">
      <section v-for="group in visibleMenus" :key="group.title" class="sidebar__group" :class="{ 'is-active-group': groupHasActiveItem(group) }">
        <button
          class="sidebar__group-trigger"
          :class="{ 'is-open': isGroupOpen(group), 'is-active': groupHasActiveItem(group) }"
          type="button"
          @click="handleGroupClick(group)"
        >
          <span v-if="!collapsed" class="sidebar__group-label">{{ group.title }}</span>
          <el-icon v-else class="sidebar__group-icon">
            <component :is="resolveDynamicComponent(group.items[0].icon)" />
          </el-icon>
          <el-icon v-if="!collapsed" class="sidebar__group-arrow">
            <component :is="isGroupOpen(group) ? ArrowUp : ArrowDown" />
          </el-icon>
        </button>

        <div v-if="isGroupOpen(group)" class="sidebar__group-items">
          <template v-for="item in group.items" :key="item.path">
            <button
              class="sidebar__item"
              :class="{ 'is-active': isActive(item.path) }"
              type="button"
              @click="handleMenuItemClick(item, group.title)"
            >
              <el-icon class="sidebar__item-icon"><component :is="resolveDynamicComponent(item.icon)" /></el-icon>
              <span v-if="!collapsed">{{ item.title }}</span>
            </button>
          </template>
        </div>
      </section>
    </div>

    <div class="sidebar__footer">
      <button class="sidebar__collapse-trigger" type="button" @click="appStore.toggleSidebar()">
        <el-icon class="sidebar__collapse-icon">
          <component :is="collapsed ? ArrowRightBold : ArrowLeftBold" />
        </el-icon>
        <span v-if="!collapsed">收起侧栏</span>
      </button>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  width: 248px;
  min-width: 248px;
  height: 100%;
  padding: 14px 10px 12px;
  border-right: 1px solid rgba(148, 163, 184, 0.16);
  background: #f3f5f7;
  color: #1f2937;
  transition: width 0.2s ease, min-width 0.2s ease;
}

.sidebar--collapsed {
  width: 84px;
  min-width: 84px;
}

.sidebar__brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px 18px;
}

.sidebar__logo {
  display: block;
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  object-fit: contain;
}

.sidebar__title {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sidebar__title span {
  color: #6b7280;
  font-size: 12px;
}

.sidebar__menus {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
}

.sidebar__group {
  overflow: visible;
  border-radius: 0;
  background: transparent;
  transition: background 0.2s ease;
}

.sidebar__group.is-active-group {
  background: transparent;
}

.sidebar__group-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 11px 12px;
  border: 0;
  background: transparent;
  border-radius: 10px;
  color: #475569;
  cursor: pointer;
  text-align: left;
  transition: background 0.2s ease, color 0.2s ease;
}

.sidebar__group-trigger.is-active,
.sidebar__group-trigger:hover {
  background: rgba(148, 163, 184, 0.1);
  color: #1f2937;
}

.sidebar__group-trigger.is-open {
  background: rgba(148, 163, 184, 0.12);
}

.sidebar__group-label {
  font-size: 14px;
  font-weight: 600;
}

.sidebar__group-arrow,
.sidebar__group-icon,
.sidebar__item-icon,
.sidebar__collapse-icon {
  color: #64748b;
}

.sidebar__group-items {
  margin-top: 4px;
  padding: 2px 0 6px;
}

.sidebar__item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  margin-top: 4px;
  padding: 11px 14px 11px 16px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: #475569;
  text-align: left;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease;
}

.sidebar__item:hover {
  background: rgba(148, 163, 184, 0.1);
  color: #1f2937;
}

.sidebar__item.is-active {
  position: relative;
  background: rgba(37, 99, 235, 0.08);
  color: #1d4ed8;
  font-weight: 600;
}

.sidebar__item.is-active::before {
  content: '';
  position: absolute;
  left: 6px;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 999px;
  background: #2563eb;
}

.sidebar__footer {
  padding-top: 10px;
}

.sidebar__collapse-trigger {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  padding: 11px 14px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.56);
  color: #334155;
  cursor: pointer;
  transition: background 0.2s ease, border-color 0.2s ease;
}

.sidebar__collapse-trigger:hover {
  background: rgba(255, 255, 255, 0.88);
  border-color: rgba(148, 163, 184, 0.3);
}

.sidebar--collapsed .sidebar__group {
  background: transparent;
}

.sidebar--collapsed .sidebar__group-trigger {
  justify-content: center;
  padding: 12px 0;
  border-radius: 10px;
}

.sidebar--collapsed .sidebar__group-items {
  display: none;
}

.sidebar--collapsed .sidebar__footer {
  padding-top: 8px;
}

.sidebar--collapsed .sidebar__collapse-trigger {
  padding: 12px 0;
}

.sidebar--collapsed .sidebar__item {
  justify-content: center;
  margin-top: 0;
  padding: 12px 0;
}
</style>
