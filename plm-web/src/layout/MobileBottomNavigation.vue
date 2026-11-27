<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Checked, Goods, House, Management, Tools, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { mobileTasksPath } from '@/utils/mobileNavigation'
import { canAccessLocalSamples } from '@/utils/sampleLocation'
import { t } from '@/locales'

type NavigationItem = {
  label: string
  to: string
  icon: typeof House
  section: 'home' | 'tasks' | 'research' | 'partners' | 'profile' | 'other'
}

const route = useRoute()
const router = useRouter()
const user = useUserStore()

const items = computed<NavigationItem[]>(() => {
  const result: NavigationItem[] = [{ label: t('首页'), to: '/mobile', icon: House, section: 'home' }]
  const tasks = mobileTasksPath(user)
  if (tasks) result.push({ label: t('待办'), to: tasks, icon: Checked, section: 'tasks' })
  if (user.hasPermission('project:view')) {
    result.push({ label: t('研发'), to: '/projects', icon: Management, section: 'research' })
  } else if (user.hasPermission('collaboration:view')) {
    result.push({ label: t('研发'), to: '/molds', icon: Tools, section: 'research' })
  }
  if (user.hasPermission('partner:view') || canAccessLocalSamples(user)) {
    result.push({
      label: t(user.hasPermission('partner:view') ? '合作' : '样品'),
      to: user.hasPermission('partner:view') ? '/partner-products' : '/samples',
      icon: Goods,
      section: 'partners'
    })
  }
  result.push({ label: t('我的'), to: '/mobile?view=profile', icon: User, section: 'profile' })
  return result
})

function sectionForCurrentRoute(): NavigationItem['section'] {
  const path = route.path
  if (path === '/mobile') {
    if (route.query.view === 'profile') return 'profile'
    return 'home'
  }
  if (path === '/dashboard') return 'home'
  if (['/my-tasks', '/pilot/tasks', '/approval-tasks'].includes(path)) return 'tasks'
  if ((path === '/projects' && !['product', 'sku'].includes(String(route.query.archiveView || '')))
    || ['/product-research', '/orders', '/production-orders', '/market-watch', '/mold-applications', '/mold-schedules', '/production-totals', '/molds', '/mold-share-mappings', '/bom', '/processes', '/inventories', '/workstations', '/quality', '/files'].includes(path)
    || path.startsWith('/product-research-')) return 'research'
  if (path.startsWith('/partner-') || ['/samples', '/sample-scan', '/model-samples', '/research-samples', '/partner-samples', '/customers', '/suppliers'].includes(path)) return 'partners'
  return 'other'
}

const activeSection = computed(sectionForCurrentRoute)

function navigate(item: NavigationItem) {
  void router.push(item.to)
}
</script>

<template>
  <nav class="mobile-bottom-nav" :aria-label="t('手机主导航')">
    <button
      v-for="item in items"
      :key="item.section"
      class="mobile-bottom-nav__item"
      :class="{ 'is-active': activeSection === item.section }"
      type="button"
      :aria-current="activeSection === item.section ? 'page' : undefined"
      @click="navigate(item)"
    >
      <el-icon class="mobile-bottom-nav__icon"><component :is="item.icon" /></el-icon>
      <span>{{ item.label }}</span>
    </button>
  </nav>
</template>

<style scoped>
.mobile-bottom-nav {
  z-index: 20;
  flex: 0 0 auto;
  display: grid;
  grid-auto-columns: minmax(0, 1fr);
  grid-auto-flow: column;
  width: 100%;
  min-height: 58px;
  padding: 5px 8px calc(5px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5e9ef;
  background: rgb(255 255 255 / 97%);
  box-shadow: 0 -2px 10px rgb(16 24 40 / 4%);
}

.mobile-bottom-nav__item {
  display: flex;
  min-width: 0;
  min-height: 48px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #687586;
  font: inherit;
  font-size: 11px;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.mobile-bottom-nav__icon {
  font-size: 20px;
}

.mobile-bottom-nav__item.is-active {
  color: #256b64;
  background: #edf5f2;
  font-weight: 600;
}
</style>
