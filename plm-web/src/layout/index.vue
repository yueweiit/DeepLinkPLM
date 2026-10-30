<template>
  <div class="layout-shell" :class="{'mobile-navigation-open':app.mobileNavigationOpen}">
    <button v-if="app.mobileNavigationOpen" class="mobile-navigation-backdrop" :aria-label='uiText("关闭手机导航")' @click="app.closeMobileNavigation()"/>
    <Sidebar />
    <div class="layout-shell__main">
      <Navbar />
      <ContentArea />
    </div>
  </div>
</template>

<script setup lang="ts">
import { t as uiText } from '@/locales'

import ContentArea from './ContentArea.vue'
import Navbar from './Navbar.vue'
import Sidebar from './Sidebar.vue'
import { watch,onBeforeUnmount } from 'vue'
import { useMediaQuery } from '@vueuse/core'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
const app=useAppStore(),route=useRoute()
const phone=useMediaQuery('(max-width:640px)')
watch(()=>route.fullPath,()=>app.closeMobileNavigation())
watch(phone,value=>{if(!value)app.closeMobileNavigation()})
onBeforeUnmount(()=>app.closeMobileNavigation())
</script>

<style scoped>
.layout-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.layout-shell__main {
  display: flex;
  flex: 1;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
}
.mobile-navigation-backdrop{display:none}
@media(max-width:640px){
 .layout-shell{height:100dvh}
 .layout-shell>:deep(.sidebar){position:fixed;inset:0 auto 0 0;width:min(248px,85vw);height:100dvh;z-index:120;transform:translateX(-100%);visibility:hidden;pointer-events:none}
 .mobile-navigation-open>:deep(.sidebar){transform:translateX(0);visibility:visible;pointer-events:auto}
 .layout-shell>:deep(.sidebar .sidebar__footer){display:none}
 .mobile-navigation-backdrop{display:block;position:fixed;inset:0;border:0;background:rgba(15,23,42,.28);z-index:110}
 .layout-shell__main{width:100%;min-width:0}
}
</style>
