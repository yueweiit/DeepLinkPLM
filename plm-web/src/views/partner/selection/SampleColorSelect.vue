<script setup lang="ts">
import { computed } from 'vue'
import { t as uiText } from '@/locales'

const props = defineProps<{ modelValue?: string[]; options?: string[]; disabled?: boolean; label: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()
const choices = computed(() => [...new Set([...(props.options || []), ...(props.modelValue || [])].filter(Boolean))])
function update(values: string[]) {
  if (props.disabled) return
  const seen = new Set<string>()
  emit('update:modelValue', values.map(value => value.trim()).filter(value => {
    const key = value.toLowerCase()
    if (!value || seen.has(key)) return false
    seen.add(key)
    return true
  }))
}
</script>

<template>
  <el-select class="sample-color-select" :model-value="modelValue || []" multiple filterable allow-create default-first-option clearable :reserve-keyword="false" :disabled="disabled" :aria-label="label" :placeholder="uiText('选择或输入颜色，回车添加')" @update:model-value="update">
    <el-option v-for="name in choices" :key="name" :label="name" :value="name" />
  </el-select>
</template>

<style scoped>
.sample-color-select{width:100%;min-width:0;font-size:12px}
.sample-color-select :deep(.el-select__wrapper){min-height:28px}
.sample-color-select :deep(.el-select__selection),.sample-color-select :deep(.el-tag){min-width:0;max-width:100%}
.sample-color-select :deep(.el-tag){height:auto;min-height:24px}
.sample-color-select :deep(.el-tag__content){white-space:normal;overflow-wrap:anywhere;line-height:18px}
</style>
