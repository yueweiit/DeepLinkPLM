<script setup lang="ts">
import { t as uiText } from '@/locales'
import type { SearchField } from '@/types/common'

defineProps<{
  field: SearchField
  modelValue: unknown
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: unknown): void
}>()
</script>

<template>
  <el-input
    v-if="field.type === 'input'"
    class="search-field-control"
    :model-value="modelValue"
    :placeholder="field.placeholder || uiText('请输入{value0}', { value0: field.label })"
    clearable
    @update:model-value="emit('update:modelValue', $event)"
  />
  <el-select
    v-else-if="field.type === 'select'"
    class="search-field-control search-field-control--select"
    :model-value="modelValue"
    :placeholder="field.placeholder || uiText('请选择{value0}', { value0: field.label })"
    clearable
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-option
      v-for="option in field.options || []"
      :key="option.value"
      :label="option.label"
      :value="option.value"
    />
  </el-select>
  <el-date-picker
    v-else
    class="search-field-control"
    :model-value="modelValue"
    type="date"
    value-format="YYYY-MM-DD"
    :placeholder="field.placeholder || uiText('请选择{value0}', { value0: field.label })"
    @update:model-value="emit('update:modelValue', $event)"
  />
</template>

<style scoped>
.search-field-control {
  min-width: 0;
}

.search-field-control--select {
  width: 180px;
}
</style>
