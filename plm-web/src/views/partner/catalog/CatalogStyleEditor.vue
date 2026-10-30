<script setup lang="ts">
import {onBeforeUnmount,ref} from 'vue'
import {ElMessageBox} from 'element-plus'
import {t} from '@/locales'
import {catalogStyle,updateCatalogStyle} from '@/api/modules/partnerCatalog'
import {newRequestId} from '@/api/modules/partner'
import type {CatalogStyle} from '@/types/partnerCatalog'
import type {SelectionRow} from '@/types/partnerSelections'
import {cloneSelection,newSelectionRow} from '@/utils/partnerSelections'
import {partnerError} from '@/utils/partnerError'
import SelectionRowEditor from '../selection/SelectionRowEditor.vue'

const props=defineProps<{item:Pick<CatalogStyle,'sourceKind'|'recordId'|'rowId'|'supplierCode'|'supplierName'>;canReadCost:boolean}>()
const emit=defineEmits<{close:[];saved:[]}>()
const row=ref<SelectionRow>(),baseline=ref<SelectionRow>(),current=ref<CatalogStyle>(),busy=ref(false),error=ref('')
const requestId=newRequestId()
const fields=['name','series','supplierItemCode','modelText','color','supplierColorIds','newSupplierColors','material','specification','moq','packaging','styleKey','configKey','unitPrice','currency','priceNote','notes','phoneModelIds','phoneScopeDeclaration','availablePhoneModelIds','unavailablePhoneModelIds','rangeRefs','pairings','rangeVersionAcknowledgements','supplyScopeNote','thickness','features','leadTimeNote','sampleFeeNote','priceOptions','priceUnit','moqQuantity','moqUnit','moqScope','moqNote','refs','sourcePosition','sameStyleKey','developmentEvidence','developmentStatus','developedOn'] as const
async function load(){busy.value=true;try{current.value=await catalogStyle(props.item.supplierCode,props.item);row.value={...newSelectionRow(),...cloneSelection(current.value as unknown as SelectionRow)};baseline.value=cloneSelection(row.value)}catch(e){error.value=partnerError(e)}finally{busy.value=false}}
function changes(){const result:Record<string,unknown>={};if(!row.value||!baseline.value)return result;for(const field of fields)if(JSON.stringify(row.value[field])!==JSON.stringify(baseline.value[field]))result[field]=row.value[field];return result as Partial<SelectionRow>}
async function close(){if(busy.value)return;if(Object.keys(changes()).length)try{await ElMessageBox.confirm(t('放弃本款尚未保存的内容？'),t('保留填写'),{confirmButtonText:t('放弃修改'),cancelButtonText:t('继续填写'),closeOnClickModal:false})}catch{return}emit('close')}
async function save(){if(busy.value||!row.value||!current.value)return;const changed=changes();if(!Object.keys(changed).length){emit('close');return}busy.value=true;error.value='';try{await updateCatalogStyle(props.item.supplierCode,current.value,{changes:changed,rowVersion:current.value.rowVersion,recordVersion:current.value.recordVersion,requestId});emit('saved')}catch(e){error.value=partnerError(e)}finally{busy.value=false}}
function beforeUnload(event:BeforeUnloadEvent){if(busy.value||Object.keys(changes()).length){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload);onBeforeUnmount(()=>window.removeEventListener('beforeunload',beforeUnload))
void load()
</script>
<template><el-dialog :model-value="true" :title="t('编辑供应商产品')" width="min(1020px,96vw)" top="20px" class="catalog-current-editor" append-to-body :close-on-click-modal="false" :before-close="close"><div v-loading="busy"><el-alert v-if="error" :title="error" type="error" :closable="false"/><p>{{t('同步当前资料；已确认报价、实收颜色与历史依据保留。')}}</p><SelectionRowEditor v-if="row" context="catalog" fixed-supplier :row="row" :files="current?.fileDetails||[]" :suppliers="[{code:item.supplierCode,name:item.supplierName}]" :can-write="!busy" :can-read-cost="canReadCost"/></div><template #footer><el-button :disabled="busy" @click="close">{{t('取消')}}</el-button><el-button type="primary" :loading="busy" :disabled="!row?.name.trim()||busy" @click="save">{{t('保存产品资料')}}</el-button></template></el-dialog></template>
<style scoped>:global(.catalog-current-editor){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-bottom:20px}:global(.catalog-current-editor>.el-dialog__body){min-height:0;overflow-y:auto}:global(.catalog-current-editor>.el-dialog__footer){display:flex;justify-content:flex-end;gap:7px;flex-wrap:wrap}p{font-size:12px;color:var(--el-text-color-secondary);line-height:1.6;margin:0 0 9px}</style>
