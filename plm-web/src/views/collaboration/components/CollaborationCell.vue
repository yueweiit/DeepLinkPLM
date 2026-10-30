<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, nextTick } from 'vue'
import type { CellValue, PlanField } from '@/types/collaboration'
import { locationNames, acceptanceNames } from '@/utils/collaboration'
import type { PhoneBrand } from '@/types/molds'
import PhoneBrandSelect from '@/views/mold/components/PhoneBrandSelect.vue'
const props = defineProps<{ field: PlanField; value: CellValue; editable: boolean; dirty?: boolean; compact?: boolean; expandable?: boolean; brands?:PhoneBrand[]; brandName?:string|null; brandSource?:string; users?: {userId:number;displayName:string;username:string}[] }>()
const emit = defineEmits<{ edit: [value: string]; preview: [value: string]; paste: [event: ClipboardEvent]; expand: [] }>()
const ownerField = computed(() => props.field.key.endsWith('UserId'))
const sharedBrand = computed(() => props.field.key==='brandId' && ['member','code'].includes(props.brandSource || ''))
const brandLabel = computed(() => (sharedBrand.value?props.brandName:props.brands?.find(b=>b.brandId===props.value)?.name || props.brandName) || '待关联')
const displayValue = computed(() => typeof props.value === 'boolean' ? (props.value ? '已确认' : '未确认') : Array.isArray(props.value) ? props.value.join('、') : props.value)
async function changed(event: Event) {
  const input = event.target as HTMLInputElement | HTMLSelectElement
  emit('edit', input.value)
  await nextTick()
  input.value = String(props.value ?? '')
}
</script>
<template>
  <div class="plan-cell" :class="{ 'is-editable': editable, 'is-dirty': dirty }">
    <template v-if="field.key==='brandId'"><span v-if="!editable || sharedBrand" class="brand-value">{{ uiText(String((brandLabel) ?? '')) }}<small v-if="sharedBrand">{{ uiText("来自手机资料") }}</small></span><PhoneBrandSelect v-else :model-value="typeof value==='number'?value:null" :brands="brands || []" :label="uiText(field.label)" :placeholder="brandName?uiText('{value0}（原始，待关联）',{value0:(brandName)}):uiText('选择已有品牌')" @update:model-value="emit('edit',$event==null?'':String($event))" /></template>
    <span v-else-if="!editable" class="readonly-value" :title="String(displayValue ?? '')">{{ field.kind === 'location' ? locationNames[String(value || 'unknown')] : field.kind === 'acceptance' ? acceptanceNames[String(value || 'pending')] : ownerField ? users?.find(u=>u.userId === value)?.displayName || value || uiText('未填写') : displayValue ?? (field.key === 'brand' ? uiText('未填写') : '—') }}</span>
    <select v-else-if="field.kind === 'location'" :aria-label="uiText(field.label)" :value="value || 'unknown'" @change="changed"><option v-for="(name,key) in locationNames" :key="key" :value="key">{{ name }}</option></select>
    <select v-else-if="field.kind === 'acceptance'" :aria-label="uiText(field.label)" :value="value || 'pending'" @change="changed"><option v-for="(name,key) in acceptanceNames" :key="key" :value="key">{{ name }}</option></select>
    <select v-else-if="ownerField" :aria-label="uiText(field.label)" :value="String(value ?? '')" @change="changed"><option value="">{{ uiText("未指定") }}</option><option v-if="value && !users?.some(u=>u.userId === value)" :value="String(value)">{{ uiText("原负责人 #") }}{{ value }}{{ uiText("（不可用）") }}</option><option v-for="u in users" :key="u.userId" :value="u.userId">{{ u.displayName || u.username }}</option></select>
    <select v-else-if="field.kind === 'part_status'" :aria-label="uiText(field.label)" :value="value ?? 'pending'" @change="changed"><option value="pending">{{ uiText("待制作") }}</option><option value="making">{{ uiText("制作中") }}</option><option value="ready">{{ uiText("已完成") }}</option></select>
    <select v-else-if="field.kind === 'currency'" :aria-label="uiText(field.label)" :value="value ?? ''" @change="changed"><option value="">{{ uiText("未填写") }}</option><option value="UNKNOWN">{{ uiText("待确认") }}</option><option v-for="c in ['MXN','CNY','USD','EUR']" :key="c" :value="c">{{ c }}</option></select>
    <textarea v-else-if="field.maxLength >= 4000 || ['notes','productionNotes','referenceUrl','pcAcceptanceIssues','tpuAcceptanceIssues','compatibilityEvidence','pcEvidence','tpuEvidence','quoteBasis'].includes(field.key)" :aria-label="field.label" :data-field="field.key" :value="String(value ?? '')" rows="2" :placeholder="compact ? '—' : '点击填写'" :maxlength="field.maxLength || undefined" @input="emit('preview',($event.target as HTMLTextAreaElement).value)" @change="changed" @paste="emit('paste',$event)" @dblclick="expandable && emit('expand')" />
    <input v-else :aria-label="uiText(field.label)" :data-field="field.key" :type="field.kind === 'date' ? 'date' : 'text'" :inputmode="['count','money'].includes(field.kind) ? 'decimal' : undefined" :value="value ?? ''" :placeholder="field.key === 'brand' ? uiText('未填写') : compact ? '—' : uiText('点击填写')" :maxlength="field.maxLength || undefined" @input="emit('preview',($event.target as HTMLInputElement).value)" @change="changed" @paste="emit('paste', $event)" @dblclick="expandable && emit('expand')" />
    <button v-if="expandable && field.maxLength >= 128" type="button" class="expand-cell" :aria-label="uiText('展开{value0}',{value0:(field.label)})" :title='uiText("展开为大输入框")' @click="emit('expand')">↗</button>
    <span v-if="dirty" class="dirty-dot" :title='uiText("尚未保存")' />
  </div>
</template>
<style scoped>
.plan-cell { min-height: 35px; display: flex; align-items: center; position: relative; border-radius: 5px; padding: 0 8px; }
.is-editable { background: #f0f6ff; border: 1px solid #e1eafe; }
.is-dirty { background: #fff5d6; border-color: #edc76a; }
input, select, textarea { color: #253b57; width: 100%; min-width: 0; border: 0; background: transparent; outline: none; font: inherit; padding: 8px 0; }
textarea { resize:none; line-height:1.5; }
input:focus, select:focus, textarea:focus { outline: 2px solid #4d82ed; outline-offset: 5px; border-radius: 2px; }
.readonly-value { color: #637387; white-space: pre-wrap; overflow-wrap: anywhere; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }.dirty-dot { flex: none; width: 5px; height: 5px; background: #b87913; border-radius: 50%; margin-left: 3px; }
.expand-cell { flex:none; border:0; background:none; color:#698bb9; cursor:pointer; padding:5px; font-size:16px; line-height:1; }
.brand-value{white-space:normal;overflow-wrap:anywhere}.brand-value small{display:block;color:#728095;font-size:12px}
</style>
