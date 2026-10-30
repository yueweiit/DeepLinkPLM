<script setup lang="ts">
import { t as uiText } from '@/locales'

import {productionStatusOptions} from '@/utils/productionStatus'
import type {ProductionStatus} from '@/types/production'
defineProps<{modelValue:ProductionStatus|''}>()
const emit=defineEmits<{'update:modelValue':[ProductionStatus|''];change:[]}>()
function change(event:Event){emit('update:modelValue',(event.target as HTMLSelectElement).value as ProductionStatus|'');emit('change')}
</script>
<template><select :value="modelValue" class="production-filter" :aria-label='uiText("产品生产状态筛选")' @change="change"><option value="">{{ uiText("全部生产状态") }}</option><option v-for="s in productionStatusOptions" :key="s.value" :value="s.value">{{uiText(String((s.label) ?? ''))}}</option></select></template>
<style scoped>.production-filter{height:28px;width:156px;max-width:100%;border:1px solid var(--el-border-color);border-radius:4px;background:var(--el-bg-color);color:var(--el-text-color-regular);padding:0 6px;font:inherit;font-size:12px}.production-filter:focus{outline:2px solid var(--el-color-primary-light-7)}</style>
