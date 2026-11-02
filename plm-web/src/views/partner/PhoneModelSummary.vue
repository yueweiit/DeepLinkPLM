<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { t } from '@/locales'
import { matchesPhone, phoneSeries, selectionSearchTerm } from '@/components/PhoneModelMultiSelect/selection'
import { comparePhoneCodes } from '@/utils/productionStatus'
import { catalogDisplayPhones, catalogPhoneGroups, catalogPhoneSummary, type SummaryPhone } from './catalog/catalogPresentation'

const props = defineProps<{ phones: SummaryPhone[]; count: number; fallback: string; originalText?: string; scope?: 'selection' | 'supply' | 'listing' }>()
const opened = ref(false), keyword = ref(''), brandFilter = ref(''), page = ref(1)
const knownPhones = computed(() => catalogDisplayPhones(props.phones))
const complete = computed(() => (props.phones.every(phone => !!phone.phoneModelId) ? new Set(props.phones.map(phone => phone.phoneModelId)).size : props.phones.length) >= props.count)
const displayCount = computed(() => complete.value ? knownPhones.value.length : props.count)
const isCombined = (phone: SummaryPhone) => phoneSeries({ ...phone, modelName: phone.modelName || phone.displayName }).key.endsWith(':combined')
const singlePhones = computed(() => knownPhones.value.filter(phone => !isCombined(phone)))
const combinedCount = computed(() => knownPhones.value.length - singlePhones.value.length)
const groups = computed(() => catalogPhoneGroups(knownPhones.value))
const summaryGroups = computed(() => catalogPhoneGroups(singlePhones.value))
const brands = computed(() => {
  const result = new Map<string, { key: string; name: string; count: number }>()
  for (const phone of knownPhones.value) {
    const key = phoneBrandKey(phone), brand = result.get(key) || { key, name: phone.brandName || t('其他型号'), count: 0 }
    brand.count++; result.set(key, brand)
  }
  return [...result.values()]
})
// Search the original members before collapsing duplicates so all supplied aliases remain searchable.
const filtered = computed(() => {
  const matching = props.phones.filter(phone => (!brandFilter.value || phoneBrandKey(phone) === brandFilter.value) && matchesPhone(phone, keyword.value))
  return catalogDisplayPhones(matching).sort(comparePhoneCodes)
})
const series = computed(() => sectionsFor(filtered.value.filter(phone => !isCombined(phone))))
const combinations = computed(() => sectionsFor(filtered.value.filter(isCombined)))
const visible = computed(() => series.value.slice((page.value - 1) * 12, page.value * 12))
const title = computed(() => t(props.scope === 'supply' ? '供应商可供手机型号' : props.scope === 'listing' ? '经营手机型号' : '已选手机型号'))
const countLabel = computed(() => props.scope === 'supply' ? '可供 {value0} 个型号' : props.scope === 'listing' ? '经营 {value0} 个型号' : '已选 {value0} 个型号')
const searchLabel = computed(() => t(props.scope === 'supply' || props.scope === 'listing' ? '搜索手机型号' : '搜索已选手机型号'))
const countText = computed(() => complete.value && combinedCount.value
  ? t('{value0} 个手机型号 · {value1} 组组合适配', { value0: singlePhones.value.length, value1: combinedCount.value })
  : t(countLabel.value, { value0: displayCount.value }))
const linkText = computed(() => complete.value && combinedCount.value
  ? countText.value + ' · ' + t('查看明细')
  : displayCount.value ? t('共 {value0} 个型号 · 查看明细', { value0: displayCount.value }) : t('查看型号原文'))
const compactText = computed(() => complete.value && knownPhones.value.length ? catalogPhoneSummary({ phones: knownPhones.value }) : props.fallback)
watch([keyword, brandFilter], () => { page.value = 1 })
function open() { keyword.value = ''; brandFilter.value = ''; page.value = 1; opened.value = true }
function phoneBrandKey(phone: SummaryPhone) { return phone.brandId ? 'id:' + phone.brandId : 'name:' + (phone.brandName || '').normalize('NFKC').toLowerCase() }
function sectionsFor(phones: SummaryPhone[]) {
  return catalogPhoneGroups(phones).sort((a, b) => groups.value.findIndex(group => group.name === a.name) - groups.value.findIndex(group => group.name === b.name)).flatMap(group => {
    const sections = new Map<string, { key: string; name: string; brand: string; phones: SummaryPhone[] }>()
    for (const phone of group.phones) {
      const modelName = selectionSearchTerm(phone.modelName || phone.displayName || '').replace(/^(?:(?:apple|苹果|samsung|三星|sm|galaxy)\s+)+/i, '')
      const item = phoneSeries({ ...phone, modelName }), key = `${group.name}:${item.key}`
      const section = sections.get(key) || { key, name: item.name, brand: group.name, phones: [] }
      section.phones.push(phone); sections.set(key, section)
    }
    return [...sections.values()].sort((a, b) => b.name.localeCompare(a.name, 'en', { numeric: true }))
  })
}
</script>

<template>
  <div class="phone-model-summary">
    <template v-if="displayCount > 2 && complete && singlePhones.length">
      <div v-for="group in summaryGroups" :key="group.name" class="phone-family">
        <strong class="model-title">{{ group.label }}</strong>
        <span v-if="summaryGroups.length > 1">{{ t(countLabel, { value0: group.phones.length }) }}</span>
      </div>
    </template>
    <strong v-else class="model-title">{{ !complete && displayCount > 2 ? countText : compactText }}</strong>
    <el-button v-if="count > 2 || ((scope === 'supply' || scope === 'listing') && displayCount > 0) || (originalText || '').length > 60" class="all-models" link type="primary" @click="open">{{ linkText }}</el-button>
    <el-dialog v-model="opened" :title="title" width="min(900px,96vw)" top="20px" class="selected-phone-details" append-to-body destroy-on-close>
      <template v-if="phones.length">
        <div v-if="brands.length > 1" class="phone-brand-filters" role="group" :aria-label="t('按品牌查看')">
          <el-button :type="brandFilter === '' ? 'primary' : 'default'" plain size="small" @click="brandFilter = ''">{{ t('全部') }} · {{ displayCount }}</el-button>
          <el-button v-for="brand in brands" :key="brand.key" :type="brandFilter === brand.key ? 'primary' : 'default'" plain size="small" @click="brandFilter = brand.key">{{ brand.name }} · {{ brand.count }}</el-button>
        </div>
        <el-input v-model="keyword" clearable :placeholder="searchLabel" :aria-label="searchLabel"/>
        <p class="phone-count">{{ countText }}<span v-if="keyword"> · {{ t('匹配 {value0} 个', { value0: filtered.length }) }}</span></p>
        <section v-for="group in visible" :key="group.key" class="selected-phone-group">
          <h3><span>{{ group.name.startsWith(group.brand) ? t(group.name) : group.brand + ' · ' + t(group.name) }}</span><small>{{ group.phones.length }}</small></h3>
          <div class="selected-phone-grid">
            <div v-for="(phone,index) in group.phones" :key="phone.phoneModelId || group.key + ':' + index" class="selected-phone-member">
              <span>{{ phone.displayName || phone.modelName }}</span><small>{{ phone.phoneModelCode || t('编码待分配') }}<template v-if="phone.productionStatus === 'discontinued'"> · {{ t('已停产') }}</template></small>
            </div>
          </div>
        </section>
        <details v-if="combinations.length" class="phone-combinations" :open="!!keyword">
          <summary>{{ t('组合适配说明（{value0}组）', { value0: combinations.reduce((sum, group) => sum + group.phones.length, 0) }) }}</summary>
          <p class="phone-count">{{ t('组合适配按原文保留，手机型号单独计数。') }}</p>
          <section v-for="group in combinations" :key="group.key" class="selected-phone-group">
            <h3><span>{{ group.brand }}</span><small>{{ group.phones.length }}</small></h3>
            <div class="selected-phone-grid">
              <div v-for="(phone,index) in group.phones" :key="phone.phoneModelId || group.key + ':' + index" class="selected-phone-member">
                <span>{{ phone.displayName || phone.modelName }}</span><small>{{ phone.phoneModelCode || t('编码待分配') }}<template v-if="phone.productionStatus === 'discontinued'"> · {{ t('已停产') }}</template></small>
              </div>
            </div>
          </section>
        </details>
        <el-empty v-if="!filtered.length" :description="t('没有匹配的型号')"/>
        <el-pagination v-if="series.length > 12" v-model:current-page="page" :total="series.length" :page-size="12" :pager-count="5" layout="prev,pager,next"/>
      </template>
      <p v-else class="phone-original">{{ originalText || fallback }}</p>
      <template #footer><el-button @click="opened = false">{{ t('关闭') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.phone-combinations{border-top:1px solid var(--el-border-color-lighter);padding-top:9px;margin-top:9px}.phone-combinations>summary{cursor:pointer;color:var(--el-color-primary);font-size:13px;line-height:1.7}.phone-model-summary{min-width:0}.phone-family{display:flex;gap:3px 8px;align-items:baseline;flex-wrap:wrap;line-height:1.5}.model-title{color:#284d7c;font-size:14px;overflow-wrap:anywhere}.phone-family>span,.phone-count{font-size:12px;color:var(--el-text-color-secondary)}.all-models{white-space:normal;height:auto;line-height:1.6;margin-top:3px}.phone-brand-filters{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:10px}.phone-brand-filters .el-button{margin:0}.selected-phone-group{border:1px solid var(--el-border-color-lighter);border-radius:6px;margin-bottom:10px;overflow:hidden}.selected-phone-group h3{display:flex;align-items:baseline;flex-wrap:wrap;gap:8px;font-size:13px;margin:0;padding:8px 10px;background:#f2f6fb;color:#284d7c}.selected-phone-group h3>span{font-size:14px}.selected-phone-group h3>small{margin-left:auto;font-weight:400}.selected-phone-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(230px,100%),1fr));gap:0 12px;padding:0 10px}.selected-phone-member{display:flex;gap:10px;align-items:baseline;justify-content:space-between;padding:9px 0;border-bottom:1px solid var(--el-border-color-lighter);overflow-wrap:anywhere;font-size:14px;min-width:0}.selected-phone-member small{color:var(--el-text-color-secondary);flex-shrink:0;font-size:12px}.phone-original{white-space:pre-wrap;overflow-wrap:anywhere}:global(.selected-phone-details){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-bottom:20px}:global(.selected-phone-details>.el-dialog__body){min-height:0;overflow-y:auto}.phone-model-summary :deep(.el-pagination){margin-top:10px;flex-wrap:wrap}
</style>
