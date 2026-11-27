<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed } from 'vue'
import { useMediaQuery } from '@vueuse/core'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Expand, Fold } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { launchStage, legacyResearchStep } from '@/constants/productResearch'
import { useClientUpdate } from '@/composables/useClientUpdate'
import { confirmLogout } from '@/utils/confirmLogout'
import { isMobileNavigationRoot, mobileBottomRootForPath, parseMobileLocation } from '@/utils/mobileNavigation'
import { t } from '@/locales'
import LanguageSwitcher from '@/components/LanguageSwitcher/index.vue'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()
const clientUpdate=useClientUpdate()
const phone=useMediaQuery('(max-width:767px)')
function toggleNavigation(){appStore.toggleSidebar()}

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
const mobileTitle = computed(() => {
  if (route.path === '/mobile') {
    if (route.query.view === 'more') return uiText('全部业务')
    if (route.query.view === 'profile') return uiText('个人中心')
    return 'DeepLink PLM'
  }
  if (route.path === '/approval-tasks' && route.query.view === 'tasks') return uiText('审批待办')
  return t(String(route.meta?.title || 'DeepLink PLM'))
})
const mobileBackTarget = computed(() => {
  if (route.path === '/partner-products'
    && route.query.supplierCode
    && ['quote', 'selection'].includes(String(route.query.sourceKind || ''))
    && Number(route.query.recordId) > 0
    && route.query.rowId) {
    const query = { ...route.query }
    delete query.sourceKind
    delete query.recordId
    delete query.rowId
    return { path: '/partner-products', query }
  }
  const source = parseMobileLocation(route.query.mobileFrom)
  if (source) return source
  if (route.path === '/mobile' && route.query.view === 'more') return { path: '/mobile', query: {} }
  if (isMobileNavigationRoot(route.path, route.query, userStore)) return undefined
  return mobileBottomRootForPath(route.path, route.query, userStore) || { path: '/mobile', query: {} }
})
const canReturnToMobileHome = computed(() => Boolean(mobileBackTarget.value))

function returnToMobileHome() {
  const target = mobileBackTarget.value
  if (target) void router.push(target)
}

async function handleLogout() {
  await confirmLogout(async () => {
    const result = await userStore.logout()
    await router.push('/login')
    if (result.serverRevoked) ElMessage.success(t('退出成功'))
    else ElMessage.warning(t('本机已退出，服务端注销未确认'))
  })
}
</script>

<template>
  <header v-if="phone" class="navbar navbar--mobile">
    <el-button
      v-if="canReturnToMobileHome"
      class="navbar__mobile-back"
      :icon="ArrowLeft"
      :aria-label='uiText("返回上一页")'
      :title='uiText("返回上一页")'
      @click="returnToMobileHome"
    />
    <span v-else class="navbar__mobile-spacer" aria-hidden="true" />
    <div class="navbar__mobile-title"><strong>{{ mobileTitle }}</strong><small v-if="route.path === '/mobile' && !['more', 'profile'].includes(String(route.query.view || ''))">{{ t('产品研发协同平台') }}</small></div>
    <span class="navbar__mobile-spacer" aria-hidden="true" />
  </header>

  <header v-else class="navbar">
    <div class="navbar__left">
      <el-button circle :icon="appStore.sidebarCollapsed ? Expand : Fold" :aria-label="uiText('切换侧栏')" @click="toggleNavigation" />
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
      <el-button class="navbar__logout" text @click="handleLogout">{{ t('退出') }}</el-button>
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

.navbar__logout {
  color: #c53b3b;
  font-weight: 600;
}
.navbar__logout:hover,
.navbar__logout:focus-visible {
  color: #a52f2f;
  background: #fff1f0;
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
 .navbar__route{overflow:hidden;white-space:nowrap}
 .navbar__route-subtitle{display:none}
 .navbar__route :deep(.el-breadcrumb__item:not(:last-child)){display:none}
 .navbar__route :deep(.el-breadcrumb),.navbar__route :deep(.el-breadcrumb__item:last-child),.navbar__route :deep(.el-breadcrumb__inner){max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
 .navbar__route :deep(.el-breadcrumb__inner){display:inline-block;line-height:1.5;font-size:13px;vertical-align:bottom}
 .navbar :deep(.language-switcher){width:78px;max-width:78px;padding-inline:4px;font-size:12px}
}

@media(max-width:767px){
 .navbar--mobile{display:grid;grid-template-columns:40px minmax(0,1fr) auto;align-items:center;gap:8px;min-height:56px;padding:5px 10px;background:#fff;backdrop-filter:none}
 .navbar__mobile-back,.navbar__mobile-spacer{width:40px;height:40px;flex:none}
 .navbar__mobile-back{border:0;background:transparent;color:#526270;padding:0}
 .navbar__mobile-back:hover,.navbar__mobile-back:focus-visible{background:#f1f5f4;color:#256b64}
 .navbar__mobile-title{display:flex;min-width:0;flex-direction:column;align-items:center;gap:2px;text-align:center}
 .navbar__mobile-title strong{max-width:100%;overflow:hidden;color:#263746;font-size:16px;line-height:20px;text-overflow:ellipsis;white-space:nowrap}
 .navbar__mobile-title small{color:#738296;font-size:11px;line-height:14px}
 .navbar__mobile-actions{display:flex;align-items:center;gap:5px}
 .navbar__mobile-actions :deep(.language-switcher){width:66px;max-width:66px;min-height:34px;padding-inline:3px;font-size:11px}
 .navbar__mobile-actions :deep(.el-button){min-height:38px;height:38px}
 .navbar__mobile-actions :deep(.navbar__logout){width:auto;padding:0 4px;color:#c53b3b;font-size:13px}
}
</style>
