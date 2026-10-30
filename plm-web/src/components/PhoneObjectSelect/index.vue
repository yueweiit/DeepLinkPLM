<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, inject, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { ArrowDown, Search } from '@element-plus/icons-vue'
import { formContextKey, type InputInstance } from 'element-plus'
import { onClickOutside, useEventListener } from '@vueuse/core'
import { useUserStore } from '@/stores/user'
import { objectKey, selectionKey as referenceKey, type PhoneObjectOption as ReferenceOption, type PhoneObjectQuery as ReferenceBrowserQuery, type ObjectPhoneGroup as ReferencePhoneGroup, type PhoneObjectKind, type PhoneObjectProvider } from './types'
import { phoneSeries, selectionSearchTerm } from '@/components/PhoneModelMultiSelect/selection'

const props = withDefaults(defineProps<{
  modelValue: ReferenceOption[]; provider: PhoneObjectProvider; kinds: PhoneObjectKind[]; multiple?: boolean; disabled?: boolean
  placeholder?: string; ariaLabel?: string; maxSelected?: number; scopeKey?: string; initialKind?: string
}>(), { multiple: true, disabled: false, maxSelected: 300, placeholder: '请选择品牌、型号及关联对象', ariaLabel: '关联对象' })
const emit = defineEmits<{ 'update:modelValue': [ReferenceOption[]] }>()
const user = useUserStore(), form = inject(formContextKey, undefined)
const popperOptions = { strategy: 'fixed' as const, modifiers: [{ name: 'preventOverflow', options: { padding: 12, altAxis: true, tether: false } }] }
const popperStyle = { padding: '0', maxWidth: 'calc(100vw - 24px)' }
const disabled = computed(() => props.disabled || !!form?.disabled || !user.token)
const selectionError = ref('')
const opened = ref(false), keyword = ref(''), brandId = ref<number>(), phoneId = ref<number>(), phoneName = ref(''), productLineId = ref<number>()
const phoneRows = ref<ReferencePhoneGroup[]>([]), unlinkedCount = ref(0), unlinkedPhone = ref(false)
const rows = ref<ReferenceOption[]>([]), brands = ref<{ brandId: number; name: string }[]>([]), lines = ref<{ productLineId: number; name: string }[]>([])
const page = ref(1), total = ref(0), loading = ref(false), error = ref(''), draft = ref<ReferenceOption[]>([]), openingKeys = ref(''), externalChanged = ref(false)
const trigger = ref<HTMLButtonElement>(), panel = ref<HTMLElement>(), searchInput = ref<InputInstance>(), scroll = ref<HTMLElement>()
const height = ref(620), panelId = `reference-select-${useId()}`, PAGE_SIZE = 50
const kinds = computed(() => props.kinds)
const kindKey = ref('model'), kind = computed(() => kinds.value.find(k => k.key === kindKey.value) || kinds.value[0])
const browsingPhones = computed(() => !!kind.value?.phoneFirst && !phoneId.value && !unlinkedPhone.value)
const phoneGroups = computed(() => {
  const values: { key: string; seriesKey: string; name: string; brandName: string; phones: ReferencePhoneGroup[] }[] = []
  for (const phone of phoneRows.value) {
    const group = phoneSeries(phone), previous = values.at(-1)
    // Group consecutive rows only: a combination must not pull lower codes above newer models.
    if (previous?.seriesKey === group.key) previous.phones.push(phone)
    else values.push({ key: `${group.key}:${phone.phoneModelId}`, seriesKey: group.key, name: group.name, brandName: phone.brandName, phones: [phone] })
  }
  return values
})

const keys = (values: ReferenceOption[]) => [...new Set(values.map(referenceKey))].sort().join('|')
const selectedKeys = computed(() => new Set(draft.value.map(objectKey)))
const allPageSelected = computed(() => !!rows.value.length && rows.value.filter(r => !r.disabled).every(r => selectedKeys.value.has(objectKey(r))) && rows.value.some(r => !r.disabled))
const somePageSelected = computed(() => !allPageSelected.value && rows.value.some(r => selectedKeys.value.has(objectKey(r))))
const preview = computed(() => props.modelValue.slice(0, 2))
const selectedBrands = computed(() => {
  const counts = new Map<number, number>()
  for (const row of draft.value) for (const id of new Set(row.phoneModels?.map(p => p.brandId) || [])) counts.set(id, (counts.get(id) || 0) + 1)
  return counts
})
const groups = computed(() => {
  const grouped = new Map<string, { key: string; title: string; phones: NonNullable<ReferenceOption['phoneModels']>; rows: ReferenceOption[] }>()
  for (const row of rows.value) {
    const phones = row.phoneModels || []
    const key = phones.length ? phones.map(p => `${p.brandId}:${p.phoneModelId || `${p.phoneModelCode || 'unassigned'}:${p.modelName}`}`).sort().join('|') : row.type === 'product' && row.productType === 'product_line' ? 'general' : 'unlinked'
    const names = [...new Set(phones.map(p => `${p.phoneModelCode || '编码待分配'} · ${p.brandName} · ${p.modelName}`))]
    const title = names.length ? names.join(' / ') : key === 'general' ? '产品通用资料' : row.modelName ? `${row.phoneModelCode || '编码待核对'} · ${row.modelName}` : '尚未关联标准手机的资料'
    if (!grouped.has(key)) grouped.set(key, { key, title, phones, rows: [] })
    grouped.get(key)!.rows.push(row)
  }
  return [...grouped.values()]
})
let generation = 0, timer: ReturnType<typeof setTimeout> | undefined, initializing = false, restoreFocus = true
function measure() {
  if (!opened.value || !trigger.value) return
  const rect = trigger.value.getBoundingClientRect()
  height.value = Math.min(620, Math.max(300, rect.top - 20, innerHeight - rect.bottom - 20), innerHeight - 24)
}
function focusSearch() { measure(); searchInput.value?.focus() }
async function load(next = 1) {
  clearTimeout(timer)
  const ticket = ++generation, auth = user.token
  if (!opened.value || disabled.value || !kind.value) return
  page.value = next; loading.value = true; error.value = ''
  const query: ReferenceBrowserQuery = { type: kind.value.type, ...'productType' in kind.value ? { productType: kind.value.productType } : {}, keyword: selectionSearchTerm(keyword.value), brandId: brandId.value, phoneModelId: phoneId.value, productLineId: productLineId.value, unlinkedPhone: unlinkedPhone.value || undefined, page: next, size: PAGE_SIZE }
  try {
    if (browsingPhones.value) {
      const result = await props.provider.phones(query)
      if (ticket !== generation || auth !== user.token || !opened.value) return
      phoneRows.value = result.content; rows.value = []; total.value = result.totalElements; brands.value = result.brands; lines.value = result.productLines; unlinkedCount.value = result.unlinkedCount
    } else {
      const result = await props.provider.page(query)
      if (ticket !== generation || auth !== user.token || !opened.value) return
      rows.value = result.content; phoneRows.value = []; total.value = result.totalElements; brands.value = result.brands; lines.value = result.productLines
      const known = new Map(rows.value.map(r => [objectKey(r), r]))
      draft.value = draft.value.map(r => known.has(objectKey(r)) ? { ...known.get(objectKey(r))!, partType: r.partType } : r)
    }
    await nextTick(); if (scroll.value) scroll.value.scrollTop = 0
  } catch (e) { if (ticket === generation && auth === user.token) { rows.value = []; phoneRows.value = []; total.value = 0; error.value = e instanceof Error ? e.message : '关联对象读取失败，请重试' } }
  finally { if (ticket === generation) loading.value = false }
}
function refresh(debounce = false) {
  if (initializing || !opened.value) return
  clearTimeout(timer); generation++; rows.value = []; phoneRows.value = []; total.value = 0; loading.value = true; error.value = ''
  if (debounce) timer = setTimeout(() => void load(1), 250); else void load(1)
}
function setBrand(id?: number) { unlinkedPhone.value = false; brandId.value = id; phoneId.value = undefined; phoneName.value = ''; refresh() }
function setKind(key: string) { unlinkedPhone.value = false; kindKey.value = key; productLineId.value = undefined; phoneId.value = undefined; phoneName.value = ''; refresh() }
function narrowPhone(id: number, name: string) { unlinkedPhone.value = false; phoneId.value = id; phoneName.value = name; refresh() }
function showUnlinked() { phoneId.value = undefined; unlinkedPhone.value = true; refresh() }
function backToPhones() { phoneId.value = undefined; unlinkedPhone.value = false; phoneName.value = ''; refresh() }
function toggle(row: ReferenceOption) {
  if (disabled.value || row.disabled) return
  selectionError.value = ''
  const key = objectKey(row)
  if (!selectedKeys.value.has(key) && props.multiple && draft.value.length >= props.maxSelected) { selectionError.value = `最多选择 ${props.maxSelected} 项，请缩小范围后选择`; return }
  draft.value = selectedKeys.value.has(key) ? draft.value.filter(r => objectKey(r) !== key) : props.multiple ? [...draft.value, row] : [row]
}
function selectPage() {
  if (!props.multiple || loading.value || error.value || disabled.value) return
  selectionError.value = ''
  if (!allPageSelected.value && draft.value.length + rows.value.filter(r => !r.disabled && !selectedKeys.value.has(objectKey(r))).length > props.maxSelected) { selectionError.value = `最多选择 ${props.maxSelected} 项，本页尚未勾选；请缩小范围后选择`; return }
  const currentKeys = new Set(rows.value.filter(r => !r.disabled).map(objectKey))
  draft.value = allPageSelected.value ? draft.value.filter(r => !currentKeys.has(objectKey(r))) : [...draft.value, ...rows.value.filter(r => !r.disabled && !selectedKeys.value.has(objectKey(r)))]
}
async function open() {
  if (disabled.value) return
  initializing = true; selectionError.value = ''; openingKeys.value = keys(props.modelValue); draft.value = [...props.modelValue]; externalChanged.value = false
  keyword.value = ''; unlinkedPhone.value = false; unlinkedCount.value = 0; brandId.value = undefined; phoneId.value = undefined; phoneName.value = ''; productLineId.value = undefined
  kindKey.value = kinds.value.find(k => k.type === props.modelValue[0]?.type && (!k.productType || k.productType === props.modelValue[0]?.productType))?.key || kinds.value.find(k=>k.key===props.initialKind)?.key || kinds.value[0]?.key || ''
  restoreFocus = true; opened.value = true; initializing = false
  await nextTick(); focusSearch(); await load(1)
}
function cancel(focus = true) { restoreFocus = focus; opened.value = false; clearTimeout(timer); generation++; loading.value = false; rows.value = []; draft.value = []; error.value = '' }
function confirm() {
  if (disabled.value || externalChanged.value) return
  emit('update:modelValue', draft.value.filter((r, i, all) => all.findIndex(v => referenceKey(v) === referenceKey(r)) === i)); cancel()
}
function focusBrand(event: KeyboardEvent, step: number) {
  const buttons = [...(event.currentTarget as HTMLElement).parentElement!.querySelectorAll<HTMLButtonElement>('button')]
  buttons[(buttons.indexOf(event.currentTarget as HTMLButtonElement) + step + buttons.length) % buttons.length]?.focus()
}
function trapFocus(event: KeyboardEvent) {
  if (event.key !== 'Tab' || !panel.value) return
  const nodes = [...panel.value.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),[tabindex="0"]')].filter(el => el.tabIndex >= 0 && el.getClientRects().length)
  if (event.shiftKey && document.activeElement === nodes[0]) { event.preventDefault(); nodes.at(-1)?.focus() }
  else if (!event.shiftKey && document.activeElement === nodes.at(-1)) { event.preventDefault(); nodes[0]?.focus() }
}
onClickOutside(panel, () => { if (opened.value) cancel(false) }, { ignore: [trigger] })
useEventListener(window, 'resize', measure)
useEventListener(document, 'scroll', measure, { capture: true, passive: true })
watch(keyword, () => refresh(true))
watch(productLineId, () => refresh())
watch(() => props.scopeKey, () => { cancel(false); brands.value = []; lines.value = [] })
watch(() => props.modelValue, value => { if (opened.value && keys(value) !== openingKeys.value) externalChanged.value = true }, { deep: true })
watch(opened, async value => { if (!value && restoreFocus) { await nextTick(); trigger.value?.focus({ preventScroll: true }) } })
watch(disabled, value => { if (value) cancel(false) })
watch(() => user.token, () => { cancel(false); brands.value = []; lines.value = [] })
onBeforeUnmount(() => { generation++; clearTimeout(timer) })
defineExpose({ open, cancel })
</script>
<template>
  <div class="reference-object-select">
    <el-popover :id="panelId" role="dialog" :title="uiText('{value0}选择面板',{value0:(ariaLabel)})" :visible="opened" placement="bottom-start" :width="740" :persistent="false" :show-arrow="false" popper-class="reference-object-popover" :popper-style="popperStyle" :popper-options="popperOptions" @after-enter="focusSearch">
      <template #reference><button ref="trigger" type="button" role="combobox" aria-haspopup="dialog" :aria-label="ariaLabel" :aria-expanded="opened" :aria-controls="panelId" :disabled="disabled" class="reference-trigger" @click="opened ? cancel() : open()" @keydown.down.prevent="open" @keydown.esc.prevent="cancel()"><span v-if="!modelValue.length || !user.token" class="placeholder">{{placeholder}}</span><span v-else class="trigger-tags"><el-tag v-for="r in preview" :key="referenceKey(r)" size="small" type="info">{{r.name}}</el-tag><span v-if="modelValue.length>preview.length">+{{modelValue.length-preview.length}}{{ uiText(" 个") }}</span></span><el-icon><ArrowDown/></el-icon></button></template>
      <section v-if="opened" ref="panel" class="reference-panel" :style="{height:`${height}px`}" @keydown.esc.stop.prevent="cancel()" @keydown="trapFocus">
        <header class="browser-header"><el-input ref="searchInput" v-model="keyword" :prefix-icon="Search" clearable maxlength="128" size="small" :placeholder='uiText("搜索品牌、型号、别名、产品或编码")' :aria-label='uiText("搜索关联对象")'/><nav class="reference-kinds" :aria-label='uiText("关联对象类别")'><button v-for="k in kinds" :key="k.key" type="button" :class="{active:kind?.key===k.key}" :aria-pressed="kind?.key===k.key" @click="setKind(k.key)">{{uiText(String((k.label) ?? ''))}}</button></nav></header>
        <p v-if="externalChanged" class="browser-error" role="alert">{{ uiText("外部关联已变化，请取消后重新打开，避免覆盖新内容。") }}</p>
        <p v-if="selectionError" class="browser-error" role="alert">{{selectionError}}</p>
        <div class="browser-body"><nav class="reference-brands" :aria-label='uiText("关联对象品牌")'><button type="button" :class="{active:!brandId}" :aria-current="!brandId?'true':undefined" @click="setBrand()" @keydown.down.prevent="focusBrand($event,1)" @keydown.up.prevent="focusBrand($event,-1)">{{ uiText("全部品牌") }}</button><button v-for="b in brands" :key="b.brandId" type="button" :class="{active:brandId===b.brandId}" :aria-current="brandId===b.brandId?'true':undefined" :data-brand-id="b.brandId" @click="setBrand(b.brandId)" @keydown.down.prevent="focusBrand($event,1)" @keydown.up.prevent="focusBrand($event,-1)"><span>{{b.name}}</span><small v-if="selectedBrands.get(b.brandId)" :aria-label="uiText('已选 {value0} 项',{value0:(selectedBrands.get(b.brandId))})">{{selectedBrands.get(b.brandId)}}</small></button><span v-if="!loading&&!brands.length" class="brand-hint">{{kind?.key==='product'?uiText('产品通用资料可直接选择'):uiText('暂无品牌关联')}}</span></nav>
          <div class="reference-results" :aria-busy="loading"><div class="reference-tools"><select v-if="lines.length" :value="productLineId || ''" :aria-label='uiText("关联对象产品筛选")' @change="productLineId=Number(($event.target as HTMLSelectElement).value)||undefined"><option value="">{{ uiText("全部产品／款式") }}</option><option v-for="l in lines" :key="l.productLineId" :value="l.productLineId">{{l.name}}</option></select><span>{{total}} {{browsingPhones?uiText('个手机型号'):uiText('条资料')}}</span><el-button v-if="phoneId||unlinkedPhone" size="small" link @click="backToPhones">← {{phoneId?phoneName:uiText('未关联标准手机的资料')}}{{ uiText(" · 返回型号") }}</el-button></div>
            <div class="page-tools"><el-checkbox v-if="multiple&&!browsingPhones" size="small" :model-value="allPageSelected" :indeterminate="somePageSelected" :disabled="loading||!rows.length||!!error" @change="selectPage">{{ uiText("全选本页（") }}{{rows.length}}）</el-checkbox><span v-else>{{browsingPhones?uiText('选择手机型号后，再勾选对应产品／资料'):uiText('选择一项后点击完成')}}</span><small>{{ uiText("按手机编码降序") }}</small></div>
            <div ref="scroll" class="reference-scroll"><p v-if="loading" class="browser-message" role="status">{{ uiText("正在读取关联对象…") }}</p><div v-else-if="error" class="browser-message" role="alert"><p>{{error}}</p><el-button size="small" @click="load(page)">{{ uiText("重试") }}</el-button></div><template v-else-if="browsingPhones"><section v-for="group in phoneGroups" :key="group.key" class="phone-group"><h3><span v-if="!brandId">{{group.brandName}} · </span>{{group.name}}</h3><button v-for="phone in group.phones" :key="phone.phoneModelId" type="button" class="reference-phone" :data-phone-id="phone.phoneModelId" @click="narrowPhone(phone.phoneModelId,phone.modelName)"><span><b>{{phone.phoneModelCode || uiText('待分配')}}</b>{{phone.modelName}}</span><small>{{phone.referenceCount}}{{ uiText(" 条资料 ›") }}</small></button></section><p v-if="!phoneRows.length" class="browser-message" role="status">{{ uiText("没有匹配的手机型号，请调整品牌、产品或搜索条件。") }}</p><button v-if="unlinkedCount" type="button" class="unlinked-reference" @click="showUnlinked">{{ uiText("查看尚未关联标准手机的 ") }}{{unlinkedCount}}{{ uiText(" 条历史资料 ›") }}</button></template><p v-else-if="!rows.length" class="browser-message" role="status">{{ uiText("没有匹配的") }}{{uiText(String((kind?.label) ?? ''))}}{{ uiText("，请调整品牌、产品或搜索条件。") }}</p><section v-for="group in loading||browsingPhones?[]:groups" :key="group.key" class="reference-group"><header><strong>{{group.title}}</strong><el-button v-if="group.phones.length===1&&group.phones[0].phoneModelId&&!phoneId" size="small" link @click="narrowPhone(group.phones[0].phoneModelId,group.phones[0].modelName)">{{ uiText("仅此型号") }}</el-button></header><el-checkbox v-for="row in group.rows" :key="referenceKey(row)" class="reference-row" size="small" :model-value="selectedKeys.has(objectKey(row))" :disabled="row.disabled" @change="toggle(row)"><span class="reference-name">{{row.productLineName || row.name}}<span v-if="row.color" class="reference-color"> · {{row.color}}</span><span v-if="!['product','sku','partner_spec'].includes(row.type)"> · {{row.name}}</span></span><small>{{row.code}} · {{row.name}}</small><small v-if="row.disabledReason">{{row.disabledReason}}</small><small v-if="row.subtitle && row.type!=='product' && row.type!=='sku'">{{row.subtitle}}</small></el-checkbox></section></div>
            <footer class="reference-pagination"><span>{{ uiText("每页 ") }}{{PAGE_SIZE}} {{browsingPhones?uiText('个型号'):uiText('项资料')}}</span><el-pagination v-if="total>PAGE_SIZE" size="small" :current-page="page" :page-size="PAGE_SIZE" :total="total" :pager-count="5" layout="prev,pager,next" :disabled="loading" @current-change="load"/></footer>
          </div>
        </div>
        <footer class="browser-footer"><div class="selection-heading"><strong>{{ uiText("已选择 ") }}{{draft.length}}{{ uiText(" 项") }}</strong><el-button size="small" link :disabled="!draft.length" @click="draft=[]">{{ uiText("清空全部") }}</el-button></div><div class="reference-tags" :aria-label='uiText("本次已选关联")'><el-tag v-for="r in draft" :key="referenceKey(r)" size="small" closable @close="draft=draft.filter(v=>referenceKey(v)!==referenceKey(r))">{{uiText(String((kinds.find(k=>k.type===r.type)?.label || r.type) ?? ''))}} · {{r.name}}</el-tag><span v-if="!draft.length">{{ uiText("尚未选择关联对象") }}</span></div><div class="browser-actions"><span>{{ uiText("切换品牌和搜索保留已选；完成后生效") }}</span><el-button size="small" @click="cancel()">{{ uiText("取消") }}</el-button><el-button size="small" type="primary" :disabled="externalChanged||disabled" @click="confirm">{{ uiText("完成选择（") }}{{draft.length}}）</el-button></div></footer>
      </section>
    </el-popover>
  </div>
</template>
<style scoped>
.reference-object-select{width:100%;min-width:0;font-size:12px}.reference-trigger{display:flex;align-items:center;gap:6px;width:100%;min-height:28px;padding:3px 8px;border:1px solid var(--el-border-color);border-radius:var(--el-border-radius-base);background:var(--el-fill-color-blank);color:var(--el-text-color-regular);font:inherit;text-align:left;cursor:pointer}.reference-trigger:hover:enabled{border-color:var(--el-color-primary)}.reference-trigger:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}.reference-trigger:disabled{cursor:not-allowed;background:var(--el-disabled-bg-color)}.placeholder{flex:1;color:var(--el-text-color-placeholder);line-height:20px}.trigger-tags{display:flex;flex:1;gap:4px;flex-wrap:wrap;min-width:0}.trigger-tags :deep(.el-tag){height:auto;max-width:100%}.trigger-tags :deep(.el-tag__content){white-space:normal;overflow-wrap:anywhere;line-height:18px}.reference-trigger>.el-icon{flex:none;color:var(--el-text-color-secondary)}.reference-panel{display:flex;flex-direction:column;min-width:0;min-height:0;max-height:calc(100dvh - 24px);color:var(--el-text-color-regular);background:var(--el-bg-color);font-size:12px;overflow:hidden}.browser-header{padding:10px 12px 0;flex:none;border-bottom:1px solid var(--el-border-color-lighter)}.reference-kinds{display:flex;flex-wrap:wrap;gap:4px;margin-top:8px}.reference-kinds button{font:inherit;border:0;background:transparent;min-height:28px;padding:4px 8px;color:var(--el-text-color-secondary);border-bottom:2px solid transparent;cursor:pointer}.reference-kinds button.active{color:var(--el-color-primary);border-color:var(--el-color-primary);font-weight:600}.browser-body{display:grid;grid-template-columns:130px minmax(0,1fr);flex:1;min-height:100px;overflow:hidden}.reference-brands{padding:6px;border-right:1px solid var(--el-border-color-lighter);overflow:auto}.reference-brands button{display:flex;justify-content:space-between;gap:4px;width:100%;font:inherit;min-height:32px;text-align:left;padding:6px;border:0;border-radius:4px;background:transparent;color:inherit;cursor:pointer;overflow-wrap:anywhere}.reference-brands button.active{color:var(--el-color-primary);background:var(--el-color-primary-light-9);font-weight:600}.reference-brands button:hover{background:var(--el-fill-color-light)}.reference-brands small{font-size:11px;color:var(--el-text-color-secondary);white-space:nowrap}.brand-hint{display:block;padding:8px;color:var(--el-text-color-secondary);line-height:18px}.reference-results{display:flex;flex-direction:column;min-height:0;min-width:0}.reference-tools{display:flex;align-items:center;gap:8px;padding:6px 10px;min-height:28px;flex-wrap:wrap;border-bottom:1px solid var(--el-border-color-extra-light)}.reference-tools>select{font:inherit;font-size:12px;width:190px;max-width:100%;height:28px;border:1px solid var(--el-border-color);border-radius:var(--el-border-radius-base);background:var(--el-fill-color-blank);color:var(--el-text-color-regular);padding:0 8px}.reference-tools>span{margin-left:auto;color:var(--el-text-color-secondary)}.page-tools{display:flex;justify-content:space-between;gap:6px;padding:4px 10px;align-items:center;min-height:28px}.page-tools small{font-size:12px;color:var(--el-text-color-secondary)}.reference-scroll{flex:1;min-height:0;overflow:auto;padding:0 10px}.phone-group h3{font:inherit;font-weight:500;margin:0;padding:7px 4px;background:var(--el-fill-color-extra-light);color:var(--el-text-color-secondary)}.reference-phone{display:flex;align-items:center;justify-content:space-between;gap:8px;width:100%;min-height:38px;border:0;border-bottom:1px solid var(--el-border-color-extra-light);background:transparent;text-align:left;color:inherit;font:inherit;cursor:pointer;padding:7px 4px}.reference-phone>span{display:flex;gap:8px;min-width:0;overflow-wrap:anywhere;line-height:18px}.reference-phone b{font-weight:500;color:var(--el-text-color-secondary);font-variant-numeric:tabular-nums}.reference-phone small{flex:none;font-size:12px;color:var(--el-color-primary)}.reference-phone:hover{background:var(--el-color-primary-light-9)}.reference-phone:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:-2px}.unlinked-reference{font:inherit;width:100%;color:var(--el-color-warning-dark-2);text-align:left;border:0;background:var(--el-color-warning-light-9);padding:8px;margin-block:8px;cursor:pointer}.reference-group{margin-bottom:8px}.reference-group>header{display:flex;align-items:center;justify-content:space-between;gap:6px;padding:6px 0;border-bottom:1px solid var(--el-border-color-extra-light);background:var(--el-fill-color-extra-light);line-height:18px}.reference-group strong{font-size:12px;overflow-wrap:anywhere;font-weight:500}.reference-group>header>.el-button{flex:none}.reference-row{display:flex;align-items:flex-start;height:auto;min-height:36px;margin:0;padding:5px 2px;border-bottom:1px solid var(--el-border-color-extra-light)}.reference-row :deep(.el-checkbox__input){margin-top:2px}.reference-row :deep(.el-checkbox__label){white-space:normal;overflow-wrap:anywhere;line-height:18px;min-width:0;font-size:12px}.reference-row small{display:block;font-size:12px;color:var(--el-text-color-secondary);line-height:16px}.reference-name{color:var(--el-text-color-regular)}.reference-pagination{display:flex;align-items:center;justify-content:space-between;min-height:32px;padding:4px 8px;border-top:1px solid var(--el-border-color-lighter);font-size:12px;color:var(--el-text-color-secondary)}.browser-message{padding:20px 8px;text-align:center;line-height:22px;color:var(--el-text-color-secondary)}.browser-error{padding:6px 12px;margin:0;color:var(--el-color-warning-dark-2);background:var(--el-color-warning-light-9);line-height:18px}.browser-footer{border-top:1px solid var(--el-border-color-lighter);padding:6px 12px 10px;flex:none}.selection-heading{display:flex;align-items:center;justify-content:space-between}.selection-heading strong{font-weight:500}.reference-tags{display:flex;align-content:flex-start;flex-wrap:wrap;gap:4px;max-height:72px;min-height:24px;overflow:auto;padding:4px 0;color:var(--el-text-color-secondary)}.reference-tags :deep(.el-tag){max-width:100%;height:auto}.reference-tags :deep(.el-tag__content){white-space:normal;overflow-wrap:anywhere;line-height:18px}.browser-actions{display:flex;align-items:center;gap:8px;justify-content:flex-end;margin-top:6px}.browser-actions>span{margin-right:auto;color:var(--el-text-color-secondary);line-height:18px}.browser-actions>.el-button{margin-left:0}.reference-panel :deep(.el-button),.reference-panel :deep(.el-input__inner),.reference-panel :deep(.el-checkbox__label),.reference-panel :deep(.el-select__placeholder){font-size:12px}.reference-panel :deep(.el-input__wrapper),.reference-panel :deep(.el-select__wrapper){min-height:28px}.reference-panel :deep(.el-checkbox){margin-right:0}.reference-kinds button:focus-visible,.reference-brands button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:-2px}@media(max-width:520px){.browser-body{grid-template-columns:96px minmax(0,1fr)}.browser-header,.browser-footer{padding-inline:8px}.reference-tools,.reference-scroll,.page-tools{padding-inline:6px}.browser-actions{flex-wrap:wrap}.browser-actions>span{flex-basis:100%}.reference-tools>select{width:100%}}@media(max-height:460px){.browser-body{min-height:40px}.reference-tags{max-height:36px}}
</style>
<style>.reference-object-popover>.el-popover__title{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap;border:0}</style>
