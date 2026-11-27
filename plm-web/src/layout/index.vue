<template>
  <div class="layout-shell">
    <Sidebar v-if="!phone" />
    <div class="layout-shell__main">
      <Navbar />
      <ContentArea />
    </div>
    <MobileBottomNavigation v-if="phone" />
  </div>
</template>

<script setup lang="ts">
import { t as uiText } from '@/locales'

import ContentArea from './ContentArea.vue'
import Navbar from './Navbar.vue'
import Sidebar from './Sidebar.vue'
import { watch } from 'vue'
import { useMediaQuery } from '@vueuse/core'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
import MobileBottomNavigation from './MobileBottomNavigation.vue'
const app=useAppStore(),route=useRoute()
const phone=useMediaQuery('(max-width:767px)')
watch(()=>route.fullPath,()=>app.closeMobileNavigation())
</script>

<style scoped>
.layout-shell {
  display: flex;
  height: 100dvh;
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
@media(max-width:767px){
 .layout-shell{display:flex;width:100%;height:100dvh;min-height:0;flex-direction:column}
 .layout-shell__main{width:100%;min-width:0;min-height:0;flex:1}
}
</style>
