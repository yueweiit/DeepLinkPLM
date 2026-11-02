<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { t } from '@/locales'
import type { CompatiblePhone } from '@/types/molds'
import { matchesPhone, phoneSeries, selectionSearchTerm } from '@/components/PhoneModelMultiSelect/selection'
import { comparePhoneCodes } from '@/utils/productionStatus'
import { catalogPhoneGroups } from './catalog/catalogPresentation'

const props = defineProps<{ phones: CompatiblePhone[]; count: number; fallback: string; originalText?: string }>()
const opened = ref(false), keyword = ref(''), family = ref(''), page = ref(1)
const groups = computed(() => catalogPhoneGroups(props.phones))
const filtered = computed(() => (family.value ? groups.value.find(group => group.name === family.value)?.phones || [] : props.phones).filter(phone => matchesPhone(phone, keyword.value)).sort(comparePhoneCodes))
const series = computed(() => catalogPhoneGroups(filtered.value).sort((a, b) => groups.value.findIndex(group => group.name === a.name) - groups.value.findIndex(group => group.name === b.name)).flatMap(group => {
  const sections = new Map<string, { key: string; name: string; brand: string; phones: CompatiblePhone[] }>()
  for (const phone of group.phones) {
    const modelName = selectionSearchTerm(phone.modelName).replace(/^(?:(?:apple|苹果|samsung|三星|sm|galaxy)\s+)+/i, '')
    const item = phoneSeries({ ...phone, modelName }), key = `${group.name}:${item.key}`
    const section = sections.get(key) || { key, name: item.name, brand: group.name, phones: [] }
    section.phones.push(phone); sections.set(key, section)
  }
  return [...sections.values()].sort((a, b) => Number(a.key.endsWith(':combined')) - Number(b.key.endsWith(':combined')) || b.name.localeCompare(a.name, 'en', { numeric: true }))
}))
const visible = computed(() => series.value.slice((page.value - 1) * 12, page.value * 12))
watch([keyword, family], () => { page.value = 1 })
function open() { keyword.value = ''; family.value = ''; page.value = 1; opened.value = true }
</script>

<template>
  <div class="phone-model-summary">
    <template v-if="count > 2 && phones.length === count">
      <div v-for="group in groups" :key="group.name" class="phone-family">
        <strong class="model-title">{{ group.label }}</strong>
        <span v-if="groups.length > 1">{{ t('已选 {value0} 个型号', { value0: group.phones.length }) }}</span>
      </div>
    </template>
    <strong v-else class="model-title">{{ count > 2 ? t('已选 {value0} 个型号', { value0: count }) : fallback }}</strong>
    <el-button v-if="count > 2 || (originalText || '').length > 60" class="all-models" link type="primary" @click="open">
      {{ count > 2 ? t('共 {value0} 个型号 · 查看明细', { value0: count }) : t('查看型号原文') }}
    </el-button>
    <el-dialog v-model="opened" :title="t('已选手机型号')" width="min(900px,96vw)" top="20px" class="selected-phone-details" append-to-body>
      <template v-if="phones.length">
        <div v-if="groups.length > 1" class="phone-brand-filters" role="group" :aria-label="t('按品牌查看')">
          <el-button :type="family === '' ? 'primary' : 'default'" plain size="small" @click="family = ''">{{ t('全部') }} · {{ count }}</el-button>
          <el-button v-for="group in groups" :key="group.name" :type="family === group.name ? 'primary' : 'default'" plain size="small" @click="family = group.name">{{ group.name }} · {{ group.phones.length }}</el-button>
        </div>
        <el-input v-model="keyword" clearable :placeholder="t('搜索已选手机型号')" :aria-label="t('搜索已选手机型号')"/>
        <p class="phone-count">{{ t('已选 {value0} 个型号', { value0: count }) }}<span v-if="keyword"> · {{ t('匹配 {value0} 个', { value0: filtered.length }) }}</span></p>
        <section v-for="group in visible" :key="group.key" class="selected-phone-group">
          <h3><span>{{ group.name.startsWith(group.brand) ? t(group.name) : group.brand + ' · ' + t(group.name) }}</span><small>{{ group.phones.length }}</small></h3>
          <div class="selected-phone-grid">
            <div v-for="phone in group.phones" :key="phone.phoneModelId" class="selected-phone-member">
              <span>{{ phone.displayName || phone.modelName }}</span><small>{{ phone.phoneModelCode || t('编码待分配') }}</small>
            </div>
          </div>
        </section>
        <el-empty v-if="!filtered.length" :description="t('没有匹配的型号')"/>
        <el-pagination v-if="series.length > 12" v-model:current-page="page" :total="series.length" :page-size="12" :pager-count="5" layout="prev,pager,next"/>
      </template>
      <p v-else class="phone-original">{{ originalText || fallback }}</p>
      <template #footer><el-button @click="opened = false">{{ t('关闭') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.phone-model-summary{min-width:0}.phone-family{display:flex;gap:3px 8px;align-items:baseline;flex-wrap:wrap;line-height:1.5}.model-title{color:#284d7c;font-size:14px;overflow-wrap:anywhere}.phone-family>span,.phone-count{font-size:12px;color:var(--el-text-color-secondary)}.all-models{white-space:normal;height:auto;line-height:1.6;margin-top:3px}.phone-brand-filters{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:10px}.phone-brand-filters .el-button{margin:0}.selected-phone-group{border:1px solid var(--el-border-color-lighter);border-radius:6px;margin-bottom:10px;overflow:hidden}.selected-phone-group h3{display:flex;align-items:baseline;flex-wrap:wrap;gap:8px;font-size:13px;margin:0;padding:8px 10px;background:#f2f6fb;color:#284d7c}.selected-phone-group h3>span{font-size:14px}.selected-phone-group h3>small{margin-left:auto;font-weight:400}.selected-phone-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(230px,100%),1fr));gap:0 12px;padding:0 10px}.selected-phone-member{display:flex;gap:10px;align-items:baseline;justify-content:space-between;padding:9px 0;border-bottom:1px solid var(--el-border-color-lighter);overflow-wrap:anywhere;font-size:14px;min-width:0}.selected-phone-member small{color:var(--el-text-color-secondary);flex-shrink:0;font-size:12px}.phone-original{white-space:pre-wrap;overflow-wrap:anywhere}:global(.selected-phone-details){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-bottom:20px}:global(.selected-phone-details>.el-dialog__body){min-height:0;overflow-y:auto}.phone-model-summary :deep(.el-pagination){margin-top:10px;flex-wrap:wrap}
</style>
