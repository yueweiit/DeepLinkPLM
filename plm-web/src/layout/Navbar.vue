<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed } from 'vue'
import { useMediaQuery } from '@vueuse/core'
import { useRoute, useRouter } from 'vue-router'
import { Expand, Fold } from '@element-plus/icons-vue'

import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { launchStage, legacyResearchStep } from '@/constants/productResearch'
import { useClientUpdate } from '@/composables/useClientUpdate'
import { t } from '@/locales'
import LanguageSwitcher from '@/components/LanguageSwitcher/index.vue'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()
const clientUpdate=useClientUpdate()
const phone=useMediaQuery('(max-width:640px)')
function toggleNavigation(){if(phone.value)appStore.toggleMobileNavigation();else appStore.toggleSidebar()}

const breadcrumbItems = computed(() => {
  if (route.name === 'ProductResearch') {
    const title = route.query.step
      ? launchStage(route.query.step).name
      : route.query.stage ? launchStage(legacyResearchStep(route.query.stage)).name : '新品项目'
    return [{ key: 'research', title: '新品研发' }, { key: 'research-view', title }]
  }
  const configured = route.meta?.breadcrumb as string[] | undefined
  if (configured?.length) {
    return configured.map((title, index) => ({
      key: `${index}-${title}`,
      title
    }))
  }

  return route.matched
    .filter((item) => item.meta?.title)
    .map((item, index) => ({
      key: `${index}-${String(item.meta.title)}`,
      title: String(item.meta.title)
    }))
})

const routeSubtitle = computed(() => String(route.meta?.subtitle || ''))

async function handleLogout() {
  await userStore.logout()
  router.push('/login')
}
</script>

<template>
  <header class="navbar">
    <div class="navbar__left">
      <el-button circle :icon="appStore.sidebarCollapsed ? Expand : Fold" :aria-label="phone?uiText('打开手机导航'):uiText('切换侧栏')" @click="toggleNavigation" />
      <div class="navbar__route">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item v-for="item in breadcrumbItems" :key="item.key">
            {{ t(item.title) }}
          </el-breadcrumb-item>
        </el-breadcrumb>
        <span v-if="routeSubtitle" class="navbar__route-subtitle">{{ t(routeSubtitle) }}</span>
      </div>
    </div>
    <div class="navbar__right">
      <LanguageSwitcher />
      <el-button v-if="clientUpdate.available.value" size="small" type="warning" plain :title='uiText("先保存当前填写到草稿，再刷新加载新版")' @click="clientUpdate.refresh">{{ uiText("发现新版，保存后刷新") }}</el-button>
      <div class="navbar__user">
        <strong>{{ userStore.profile?.userName || '--' }}</strong>
        <span>{{ t(userStore.profile?.roleName || '--') }}</span>
      </div>
      <el-button text @click="handleLogout">{{ t('退出') }}</el-button>
    </div>
  </header>
</template>

<style scoped>
.navbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--plm-color-border-light);
  background: var(--plm-color-navbar);
  backdrop-filter: blur(10px);
}

.navbar__left,
.navbar__right {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.navbar__route {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex-wrap: wrap;
}

.navbar__route-subtitle {
  color: var(--plm-color-text-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}

.navbar__user {
  display: flex;
  flex-direction: column;
  gap: 2px;
  text-align: right;
}

.navbar__user span {
  color: var(--plm-color-text-secondary);
  font-size: 12px;
}

@media (max-width: 960px) {
  .navbar {
    gap: 10px;
    padding: 10px 14px;
  }

  .navbar__right {
    flex: none;
    justify-content: flex-end;
  }
  .navbar__user { max-width: 160px; overflow-wrap: anywhere; }
}
@media(max-width:640px){
 .navbar{gap:6px;padding:8px 12px;flex-wrap:nowrap;min-height:46px;flex:none}
 .navbar__left,.navbar__right{gap:7px}
 .navbar__left{flex:1;min-width:0}
 .navbar__right{width:auto;flex:none}
 .navbar__user{max-width:100px;overflow-wrap:anywhere;font-size:12px}
 .navbar__user span,.navbar__route-subtitle{display:none}
 .navbar__route :deep(.el-breadcrumb__item:not(:last-child)){display:none}
 .navbar__route :deep(.el-breadcrumb__inner){line-height:1.5;overflow-wrap:anywhere;font-size:13px}
}
</style>
