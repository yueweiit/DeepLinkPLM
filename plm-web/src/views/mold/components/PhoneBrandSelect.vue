<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ElSelect } from 'element-plus'
import type { PhoneBrand } from '@/types/molds'
const props=defineProps<{modelValue:number|null;brands:PhoneBrand[];placeholder?:string;label?:string;disabled?:boolean}>()
const emit=defineEmits<{'update:modelValue':[value:number|null]}>()
const editing=ref(false),select=ref<InstanceType<typeof ElSelect>>()
const name=computed(()=>props.brands.find(b=>b.brandId===props.modelValue)?.name || (props.modelValue?`历史品牌 #${props.modelValue}（不可用）`:props.placeholder || '选择已有品牌'))
async function open(){if(props.disabled)return;editing.value=true;await nextTick();select.value?.focus();select.value?.toggleMenu()}
function choose(value:number|null){emit('update:modelValue',value || null);editing.value=false}
</script>
<template>
  <button v-if="!editing" type="button" class="brand-trigger" :aria-label="label || '品牌'" aria-haspopup="listbox" :disabled="disabled" @click="open"><span>{{name}}</span><span aria-hidden="true">⌄</span></button>
  <el-select v-else ref="select" :model-value="modelValue" :aria-label="label || '品牌'" :placeholder="placeholder || '选择已有品牌'" :disabled="disabled" clearable filterable :allow-create="false" class="phone-brand-select" @update:model-value="choose" @visible-change="!$event && (editing=false)">
    <el-option v-if="modelValue && !brands.some(b=>b.brandId===modelValue)" :value="modelValue" :label="`历史品牌 #${modelValue}（不可用）`" disabled />
    <el-option v-for="b in brands" :key="b.brandId" :value="b.brandId" :label="`${b.name}${b.status!=='enabled'?'（已停用，历史引用）':''}`" :disabled="b.status!=='enabled'" />
  </el-select>
</template>
<style scoped>.phone-brand-select,.brand-trigger{width:100%;min-width:0}.brand-trigger{display:flex;justify-content:space-between;align-items:center;gap:6px;border:1px solid #dcdfe6;border-radius:4px;padding:5px 8px;background:white;color:inherit;font:inherit;text-align:left;cursor:pointer}.brand-trigger span:first-child{white-space:normal;overflow-wrap:anywhere}.brand-trigger:focus-visible{outline:2px solid #4d82ed}</style>
