<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, onBeforeUnmount, ref, watch } from 'vue'
import PhoneObjectSelect from '@/components/PhoneObjectSelect/index.vue'
import type { PhoneObjectOption, PhoneObjectProvider } from '@/components/PhoneObjectSelect/types'
import { operationSpecOptions, operationSpecPhones } from '@/api/modules/partnerOperations'
import { useUserStore } from '@/stores/user'
import type { BusinessData } from '@/types/partner'
const props = withDefaults(defineProps<{ modelValue: number[]; multiple?: boolean; supplierCode?: string; productId?: number; activeOnly?: boolean; disabled?: boolean; ariaLabel?: string }>(), { multiple: true, activeOnly: false, ariaLabel: '商品规格' })
const emit = defineEmits<{ 'update:modelValue': [number[]]; selected: [BusinessData[]]; resolved: [BusinessData[]] }>()
const user = useUserStore(), known = ref(new Map<number, BusinessData>()), error = ref('')
const scopeKey = computed(() => JSON.stringify([props.supplierCode, props.productId, props.activeOnly, user.token]))
const constraints = () => ({ supplierCode: props.supplierCode, productId: props.productId, activeOnly: props.activeOnly })
function option(row: BusinessData): PhoneObjectOption { return { type: 'partner_spec', id: row.id, name: row.name, code: row.websiteSku ? `${row.code} / ${row.websiteSku}` : row.code, phoneModels: row.phoneModels || [], productLineId: row.productId, productLineName: row.productName, data: row } }
const selected = computed(() => props.modelValue.map(id => known.value.has(id) ? option(known.value.get(id)!) : { type: 'partner_spec', id, name: `规格 #${id}`, code: `#${id}` }))
function remember(rows: BusinessData[]) { known.value = new Map([...known.value, ...rows.map(r => [r.id, r] as const)]); emit('resolved', rows) }
const provider: PhoneObjectProvider = {
  async page(query) { const result = await operationSpecOptions({ ...query, ...constraints() }); return { ...result, content: result.content.map(option) } },
  phones: query => operationSpecPhones({ ...query, ...constraints() })
}
let generation = 0
async function resolve() {
  const ticket = ++generation, scope = scopeKey.value, ids = [...new Set(props.modelValue)]; error.value = ''
  if (!user.token || !ids.length) return
  try {
    const all: BusinessData[] = []
    for (let offset = 0; offset < ids.length; offset += 100) {
      const result = await operationSpecOptions({ ...constraints(), ids: ids.slice(offset, offset + 100).join(','), page: 1, size: 100 })
      if (ticket !== generation || scope !== scopeKey.value) return
      all.push(...result.content)
    }
    remember(all)
  } catch (e) { if (ticket === generation && scope === scopeKey.value) error.value = e instanceof Error ? e.message : '已选规格读取失败，请重试' }
}
function confirm(rows: PhoneObjectOption[]) {
  if (rows.some(r => !r.data && !known.value.has(r.id))) { error.value = '已选规格在当前范围内尚未核实，请重试读取，或清除该项后重新选择'; return }
  const resolved = rows.map(r => r.data || known.value.get(r.id)).filter((r): r is BusinessData => !!r)
  remember(resolved); emit('update:modelValue', rows.map(r => r.id)); emit('selected', resolved)
}
watch(() => props.modelValue.join(','), () => void resolve())
watch(scopeKey, () => { generation++; known.value = new Map(); if (user.token) void resolve() }, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>
<template><div class="partner-spec-select"><PhoneObjectSelect :model-value="selected" :provider="provider" :kinds="[{key:'partner_spec',type:'partner_spec',label:'商品规格',phoneFirst:true}]" :multiple="multiple" :disabled="disabled" :scope-key="scopeKey" :aria-label="ariaLabel" :placeholder='uiText("请选择品牌、手机型号及商品规格")' @update:model-value="confirm"/><p v-if="error" role="alert">{{error}}<el-button link @click="resolve">{{ uiText("重试") }}</el-button></p></div></template>
<style scoped>.partner-spec-select{min-width:0;width:100%}.partner-spec-select>p{font-size:12px;color:var(--el-color-danger);line-height:18px;margin:4px 0}</style>
