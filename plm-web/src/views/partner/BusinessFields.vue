<script setup lang="ts">
import { t as uiText } from '@/locales'

import type {BusinessData,BusinessField} from '@/types/partner'
import { nextTick, ref } from 'vue'
import { useResizeObserver } from '@vueuse/core'
import { ElInput } from 'element-plus'
defineProps<{model:BusinessData;fields:BusinessField[];errors?:Record<string,string>}>()
const area=ref<HTMLElement>(),textInputs=ref<InstanceType<typeof ElInput>[]>([])
useResizeObserver(area,()=>{void nextTick(()=>textInputs.value.forEach(input=>input.resizeTextarea?.()))})
</script>
<template>
 <div ref="area" class="business-fields"><el-form-item v-for="f in fields" :key="f.key" :label="uiText(f.label)" :required="f.required" :error="errors?.[f.key]" class="business-field" :class="{'business-field--long':f.type==='textarea'}">
 <el-select v-if="f.type==='select'" v-model="model[f.key]" filterable clearable :placeholder='uiText("请选择")'><el-option v-for="o in f.options||[]" :key="o.value" :label="o.label" :value="o.value"/></el-select>
 <el-date-picker v-else-if="f.type==='date'" v-model="model[f.key]" type="date" value-format="YYYY-MM-DD"/>
 <el-input-number v-else-if="f.type==='number'" v-model="model[f.key]" :min="0" :precision="0" controls-position="right"/>
 <el-input v-else-if="f.type==='textarea'" ref="textInputs" v-model="model[f.key]" type="textarea" :autosize="{minRows:3}"/>
 <el-input v-else v-model="model[f.key]" :inputmode="f.type==='money'?'decimal':undefined"/>
 </el-form-item></div>
</template>
<style scoped>.business-fields{display:grid;gap:0 16px;grid-template-columns:repeat(2,minmax(0,1fr))}.business-field{min-width:0}.business-field--long{grid-column:1/-1}.business-field :deep(.el-select),.business-field :deep(.el-input-number),.business-field :deep(.el-date-editor){width:100%}.business-field :deep(textarea){line-height:1.7;resize:vertical}@media(max-width:650px){.business-fields{grid-template-columns:1fr}}</style>
