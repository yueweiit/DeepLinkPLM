<script setup lang="ts">
import { computed, ref } from 'vue'
import PhoneObjectSelect from '@/components/PhoneObjectSelect/index.vue'
import type { PhoneObjectKind, PhoneObjectOption, PhoneObjectProvider } from '@/components/PhoneObjectSelect/types'
import { getReferencePage, getReferencePhonePage, type ReferenceOption, type ReferenceType, type ReferenceBrowserQuery } from '@/api/modules/attachment'
const props = withDefaults(defineProps<{ modelValue: ReferenceOption[]; multiple?: boolean; disabled?: boolean; types?: ReferenceType[]; projectOnly?: boolean; placeholder?: string; ariaLabel?: string }>(), { multiple: true, disabled: false, projectOnly: false })
const emit = defineEmits<{ 'update:modelValue': [ReferenceOption[]] }>()
const selector = ref<InstanceType<typeof PhoneObjectSelect>>()
const kinds = computed<PhoneObjectKind[]>(() => [
  { key: 'product', type: 'product', productType: props.projectOnly ? undefined : 'product_line', label: props.projectOnly ? '项目' : '产品' },
  ...props.projectOnly ? [] : [
    { key: 'model', type: 'product', productType: 'model_variant', label: '型号', phoneFirst: true },
    { key: 'sku', type: 'sku', label: 'SKU', phoneFirst: true }, { key: 'item', type: 'item', label: '模具档案', phoneFirst: true },
    { key: 'mold_set', type: 'mold_set', label: '实物套', phoneFirst: true }, { key: 'mold_code', type: 'mold_code', label: '主档编码' }, { key: 'source_code', type: 'source_code', label: '来源编码' }
  ]
].filter(k => !props.types || props.types.includes(k.type as ReferenceType)))
const provider: PhoneObjectProvider = {
  page: query => getReferencePage({ ...query, projectOnly: props.projectOnly } as ReferenceBrowserQuery),
  phones: query => getReferencePhonePage({ ...query, projectOnly: props.projectOnly } as ReferenceBrowserQuery)
}
function confirm(rows: PhoneObjectOption[]) { emit('update:modelValue', rows as ReferenceOption[]) }
defineExpose({ open: () => selector.value?.open(), cancel: () => selector.value?.cancel() })
</script>
<template><PhoneObjectSelect ref="selector" :model-value="modelValue" :provider="provider" :kinds="kinds" initial-kind="model" :multiple="multiple" :disabled="disabled" :placeholder="placeholder" :aria-label="ariaLabel" :scope-key="JSON.stringify([projectOnly,types])" @update:model-value="confirm"/></template>
