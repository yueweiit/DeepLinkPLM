<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()
import { RESEARCH_BUSINESS, researchBusinessRoute } from '@/utils/researchNavigation'
const researchReturn = computed(() => {
  const id = Number(route.query.researchProjectId)
  const workspace = RESEARCH_BUSINESS.find(item => item.kind === route.query.researchWorkspace)
  return id > 0 && Number.isSafeInteger(id) && workspace ? researchBusinessRoute(workspace.kind,id,Number(route.query.researchStage)||1) : null
})
const partnerReturn=computed(()=>{const from=String(route.query.from||'');return /^\/(partner-selections|partner-products|partner-listings|partner-sales|partner-fulfilments|partner-operations|samples|customers|suppliers)([/?]|$)/.test(from)&&!['/partner-selections','/partner-products','/partner-listings','/partner-sales','/partner-fulfilments','/samples','/model-samples','/research-samples','/partner-samples','/suppliers'].includes(route.path)&&!route.path.startsWith('/partner-operations/')&&!route.path.startsWith('/customers')?from:null})
const content = ref<HTMLElement>()
const positions = new Map<string, number>()
watch(() => route.path, async (path, previous) => {
  if (content.value) positions.set(previous, content.value.scrollTop)
  await nextTick()
  if (content.value) content.value.scrollTop = positions.get(path) || 0
})
watch(() => userStore.token, () => positions.clear())
</script>

<template>
  <main ref="content" class="content-area" :class="{ 'content-area--collaboration': ['/partner-selections','/partner-products','/partner-listings','/partner-sales','/partner-fulfilments','/samples','/model-samples','/research-samples','/partner-samples','/customers','/suppliers','/inventories','/code-center','/files','/my-tasks','/production-totals','/molds','/phone-model-codes','/market-watch','/mold-applications','/mold-schedules','/development-budgets','/development-expenses','/product-research',...RESEARCH_BUSINESS.map(item=>item.path)].includes(route.path) || route.path.startsWith('/partner-operations/') || route.path==='/skus' || route.path==='/projects' }">
    <div v-if="researchReturn" class="research-return"><el-button link type="primary" @click="router.push(researchReturn)">{{ uiText("返回新品项目 · ") }}{{ uiText(String((RESEARCH_BUSINESS.find(item=>item.kind===route.query.researchWorkspace)?.title) ?? '')) }}</el-button><span>{{ uiText("当前查看关联业务原记录") }}</span></div>
    <div v-if="partnerReturn" class="research-return"><el-button link type="primary" @click="router.push(partnerReturn)">{{ uiText("返回来源页面") }}</el-button></div>
    <div class="content-body">
    <router-view v-slot="{ Component }">
      <KeepAlive :key="userStore.token" :include="['InventoryCenterView', 'BomCenterView', 'ProductionTotalsView', 'MoldManagementView', 'MoldScheduleView', 'DevelopmentBudgetView', 'DevelopmentExpenseView']" :max="9">
        <component :is="Component" />
      </KeepAlive>
    </router-view>
    </div>
  </main>
</template>

<style scoped>
.content-body {flex:1;min-height:0;min-width:0;}
.research-return {display:flex;align-items:center;flex-wrap:wrap;gap:8px;padding:4px 0 8px;font-size:12px;color:#64748b;}
.content-area {
  display:flex;flex-direction:column;
  flex: 1;
  min-height: 0;
  padding: 20px;
  overflow-y: auto;
  overflow-x: hidden;
}
.content-area--collaboration { padding:12px; overflow:hidden; }
</style>
