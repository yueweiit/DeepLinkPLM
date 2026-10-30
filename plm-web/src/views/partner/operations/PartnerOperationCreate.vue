<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { createOperation, type OperationKind } from '@/api/modules/partnerOperations'
import { newRequestId, partnerOptions } from '@/api/modules/partner'
import type { BusinessData, BusinessField, PartnerRow } from '@/types/partner'
import { partnerError } from '@/utils/partnerError'
import BusinessFields from '../BusinessFields.vue'
import OperationSpecPicker from './OperationSpecPicker.vue'
import { operationTitles } from './presentation'
const props = defineProps<{ kind: OperationKind; parentId?: number; supplierCode?: string; sourceName?: string; previousId?: number; specId?: number; specDescription?: string }>()
const emit = defineEmits<{ saved: [row: PartnerRow]; close: [] }>()
const form = ref<BusinessData>({ name: props.sourceName ? `${operationTitles[props.kind]} · ${props.sourceName}` : '', supplierCode: props.supplierCode || '', parentId: props.parentId, previousId: props.previousId, specId: props.specId, specDescription: props.specDescription || '', date: new Date().toLocaleDateString('sv-SE'), currency: '', lines: [{ specId: undefined, qty: undefined }], requestId: newRequestId() })
const suppliers = ref<BusinessData[]>([]), error = ref(''), busy = ref(false), dirty = ref(false)
const fieldErrors=ref<Record<string,string>>({})
void partnerOptions().then(options => { suppliers.value = options.suppliers }).catch(e => { error.value = partnerError(e) })
const fields = computed<BusinessField[]>(() => {
  const f: BusinessField[] = [{ key: 'name', label: '记录名称', required: true }]
  if (['supplier_quote', 'fulfilment'].includes(props.kind)) f.push({ key: 'supplierCode', label: '现有供应商', type: 'select', options: suppliers.value.map(s => ({ value: s.code, label: `${s.name} · ${s.code}` })), required: true })
  if (props.kind === 'supplier_quote') f.push({ key: 'specDescription', label: '供应商实际报价型号／规格（可先留草稿）', type: 'textarea' }, { key: 'supplierItemCode', label: '供方原货号（不等于网站 SKU）' }, { key: 'qty', label: '报价数量（未知留空）', type: 'number' }, { key: 'unitPrice', label: '供方单价（未知留空）', type: 'money' }, { key: 'currency', label: '实际币种（三位代码，未知留空）' }, { key: 'date', label: '实际报价日期（确认前补齐）', type: 'date' }, { key: 'terms', label: '原报价条款／采用说明', type: 'textarea' })
  if (props.kind === 'sample_fee') f.push({ key: 'amount', label: '约定样品费（未知留空，不代表已支付）', type: 'money' }, { key: 'currency', label: '实际币种（未知留空）' }, { key: 'terms', label: '本次收费、可退／可抵及条件的实际约定', type: 'textarea', required: true })
  if (props.kind === 'fulfilment') f.push({ key: 'factory', label: '实际工厂', required: true }, { key: 'sourceChannel', label: '已确认需求来源渠道', required: true }, { key: 'sourceReference', label: '原订单／渠道确认依据（可列多个）', type: 'textarea', required: true }, { key: 'requirements', label: '交期、质量、包装及来源数量说明', type: 'textarea' })
  if (props.kind === 'inspection') f.push({ key: 'factory', label: '本次实际检验工厂' })
  return f
})
async function save() { fieldErrors.value={};for(const f of fields.value.filter(f=>f.required))if(form.value[f.key]===undefined||form.value[f.key]===null||String(form.value[f.key]).trim()==='')fieldErrors.value[f.key]=`请填写${f.label}`;if(Object.keys(fieldErrors.value).length){error.value='请补充标出的基本资料；检验结果等后续内容可以稍后填写。';return}busy.value = true; error.value = ''; try { const row = await createOperation(props.kind, form.value); dirty.value = false; emit('saved', row) } catch (e) { error.value = partnerError(e) } finally { busy.value = false } }
async function discardAllowed(){if(!dirty.value)return true;try{await ElMessageBox.confirm(uiText('放弃本次未保存的填写？'),uiText('未保存内容'));return true}catch{return false}}
async function close() { if(await discardAllowed())emit('close') }
onBeforeRouteLeave(discardAllowed)
defineExpose({ dirty,close })
</script>
<template><el-form label-position="top" class="operation-create" @input="dirty = true" @change="dirty = true"><p class="hint">{{ uiText("只引用实际资料，不创建客户销售订单，不执行付款或外部写入。保存后可添加原件。") }}</p><BusinessFields :model="form" :fields="fields" :errors="fieldErrors"/><el-form-item v-if="kind === 'supplier_quote'" :label='uiText("关联已有规格（可选；未选不创建产品）")'><OperationSpecPicker v-model="form.specId" :supplier-code="form.supplierCode"/></el-form-item><section v-if="kind === 'fulfilment'"><h4>{{ uiText("本批明确规格与数量") }}</h4><div v-for="(line, i) in form.lines" :key="i" class="line"><OperationSpecPicker v-model="line.specId" :supplier-code="form.supplierCode"/><el-input-number v-model="line.qty" :min="1" :precision="0" :placeholder='uiText("数量")'/><el-button :disabled="form.lines.length === 1" @click="form.lines.splice(i, 1); dirty = true">{{ uiText("移除") }}</el-button></div><el-button @click="form.lines.push({}); dirty = true">{{ uiText("添加规格") }}</el-button></section><el-alert v-if="error" :title="error" type="error" :closable="false"/><footer><el-button @click="close">{{ uiText("取消") }}</el-button><el-button type="primary" :loading="busy" @click="save">{{ uiText("保存实际记录") }}</el-button></footer></el-form></template>
<style scoped>.operation-create{min-width:0}.hint{font-size:13px;color:#6e7e92;line-height:1.6}.line{display:flex;gap:8px;margin-bottom:10px;flex-wrap:wrap}.line>:first-child{flex:1;min-width:min(220px,100%)}footer{display:flex;justify-content:flex-end;gap:8px;margin-top:16px}h4{font-size:14px}</style>
