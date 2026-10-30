<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, reactive, watch } from 'vue'
import type { CodeItem, CodeItemPayload } from '@/api/modules/code'

const props = defineProps<{ modelValue: boolean; item?: CodeItem | null; loading?: boolean; error?:string; codeType?: 'color' | 'phone_model' }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void; (event: 'save', payload: CodeItemPayload): void }>()
const form = reactive<CodeItemPayload>({ codeType: 'color', codeValue: '', codeName: '', codeNameZh: '', sortOrder: 0 })
const isPhone = computed(() => form.codeType === 'phone_model')
const typeLabel = computed(() => isPhone.value ? '手机型号编码' : '颜色编码')
const valid = computed(() => Boolean(form.codeName.trim()) && (!props.item||Boolean(form.reason?.trim())) && (isPhone.value ? /^[0-9]{4}$/.test(form.codeValue) : Boolean(form.codeValue.trim())))

watch(() => [props.modelValue, props.item], () => {
  if (!props.modelValue) return
  form.codeType = props.item?.codeType || props.codeType || 'color'
  form.codeValue = props.item?.codeValue || ''
  form.codeName = props.item?.codeName || ''
  form.codeNameZh = props.item?.codeNameZh || ''
  form.sortOrder = props.item?.sortOrder || 0
  form.version=props.item?.version
  form.reason=''
}, { immediate: true })
</script>

<template>
  <el-dialog :model-value="modelValue" :title="uiText('{value0}{value1}',{value0:(item ? '编辑' : '新增'),value1:(typeLabel)})" width="min(520px,94vw)" :close-on-click-modal="false" :close-on-press-escape="!loading" :show-close="!loading" @close="emit('update:modelValue', false)">
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <el-form label-position="top" :disabled="loading">
      <div class="code-form-grid">
        <el-form-item :label='uiText("编码类型")'><el-input :model-value="typeLabel" disabled /></el-form-item>
        <el-form-item :label="isPhone ? uiText('型号编码（四位数字）') : uiText('颜色编码值')"><el-input v-model="form.codeValue" :disabled="Boolean(item)" :maxlength="isPhone ? 4 : 64" :placeholder="isPhone ? uiText('例如 0001，创建后不可修改') : uiText('例如 02')" /></el-form-item>
      </div>
      <el-form-item :label="isPhone ? uiText('手机型号名称') : uiText('颜色原名称')"><el-input v-model="form.codeName" :maxlength="128" :placeholder="isPhone ? uiText('例如 Apple iPhone 16') : uiText('例如 Negro')" /></el-form-item>
      <el-form-item v-if="!isPhone" :label='uiText("颜色中文名称")'><el-input v-model="form.codeNameZh" :maxlength="128" :placeholder='uiText("例如 黑色")' /></el-form-item>
      <el-form-item :label='uiText("排序")'><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
      <el-form-item v-if="item" :label='uiText("修改原因")' required><el-input v-model="form.reason" type="textarea" :rows="2" maxlength="1000" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="loading" @click="emit('update:modelValue', false)">{{ uiText("取消") }}</el-button>
      <el-button data-test="save-code-item" type="primary" :loading="loading" :disabled="!valid||loading" @click="emit('save', { ...form, codeName: form.codeName.trim() })">{{ uiText("保存") }}{{ uiText(String((typeLabel) ?? '')) }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>.code-form-grid { display: grid; grid-template-columns: 1fr; gap: 14px; } @media (max-width: 560px) { .code-form-grid { grid-template-columns: 1fr; } }</style>
