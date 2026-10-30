<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { getMoldOptions } from '@/api/modules/molds'
import { useUserStore } from '@/stores/user'
import PhoneReferenceManager from '@/views/mold/components/PhoneReferenceManager.vue'
import type { PhoneModelReviewKind } from '@/api/modules/code'

const route = useRoute(), router = useRouter(), user = useUserStore()
const editable = ref(false), loading = ref(true), error = ref('')
const phoneModelId = computed(() => {
  const value = Number(route.query.phoneModelId)
  return Number.isSafeInteger(value) && value > 0 ? value : undefined
})
const tab = computed(() => {
  const value = String(route.query.tab || '')
  if (value === 'codes') return 'groups'
  return ['groups', 'phones', 'intake', 'brands', 'review', 'issues'].includes(value) ? value : phoneModelId.value ? 'phones' : 'groups'
})
const keyword = computed(() => typeof route.query.keyword === 'string' ? route.query.keyword : '')
const reviewKind = computed<PhoneModelReviewKind>(() => route.query.kind === 'name_many_codes' ? 'name_many_codes' : 'code_many_names')
const reviewState = computed<'pending'|'confirmed'|''>(() => route.query.reviewState === 'confirmed' ? 'confirmed' : route.query.reviewState === 'all' ? '' : 'pending')
let generation = 0
async function loadPermissions() {
  const token = user.token, epoch = ++generation
  editable.value = false; loading.value = true; error.value = ''
  try {
    const options = await getMoldOptions()
    if (epoch === generation && token === user.token) editable.value = options.writableGroups.includes('engineering')
  } catch (e) {
    if (epoch === generation && token === user.token) error.value = e instanceof Error ? e.message : '维护权限读取失败，请重试'
  } finally {
    if (epoch === generation && token === user.token) loading.value = false
  }
}
function changeTab(value: string) {
  if (value === tab.value) return
  const query:LocationQueryRaw = { ...route.query, tab: value }
  delete query.phoneModelId; delete query.panel
  void router.replace({ path: '/phone-model-codes', query })
}
watch(() => user.token, () => { generation++; editable.value = false })
onMounted(() => void loadPermissions())
</script>

<template>
  <main class="phone-model-code-page" :aria-label='uiText("手机编码")'>
    <header><h2>{{ uiText("手机编码") }}</h2><span>{{loading ? uiText('权限读取中') : editable ? uiText('工程资料维护') : uiText('资料只读')}}</span></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="loadPermissions">{{ uiText("重试") }}</el-button></el-alert>
    <PhoneReferenceManager :inline="true" :model-value="true" :editable="editable" :initial-tab="tab" :keyword="keyword" :phone-model-id="phoneModelId" :coverage-on-open="route.query.panel==='coverage'" :review-kind="reviewKind" :review-state="reviewState" @tab-change="changeTab"/>
  </main>
</template>

<style scoped>
.phone-model-code-page{height:100%;min-height:0;min-width:0;display:flex;flex-direction:column;gap:4px;font-size:12px;--el-font-size-base:12px}
header{display:flex;gap:12px;align-items:center;flex:none}h2{font-size:16px;margin:0}header>span{margin-left:auto;color:var(--el-text-color-secondary)}
</style>
