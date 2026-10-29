<script setup lang="ts">
import PartnerSpecSelect from '../components/PartnerSpecSelect.vue'
import type { BusinessData } from '@/types/partner'
const props = defineProps<{ modelValue?: number; supplierCode?: string; productId?: number; activeOnly?: boolean; disabled?: boolean; ariaLabel?: string }>()
const emit = defineEmits<{ 'update:modelValue': [number | undefined]; selected: [BusinessData | undefined]; resolved: [BusinessData[]] }>()
</script>
<template><PartnerSpecSelect :model-value="modelValue ? [modelValue] : []" :multiple="false" :supplier-code="supplierCode" :product-id="productId" :active-only="activeOnly" :disabled="disabled" :aria-label="ariaLabel" @update:model-value="emit('update:modelValue',$event[0])" @selected="emit('selected',$event[0])" @resolved="emit('resolved',$event)"/></template>
