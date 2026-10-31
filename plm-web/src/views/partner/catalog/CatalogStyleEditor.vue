<script setup lang="ts">
import {onBeforeUnmount,ref} from 'vue'
import {ElMessageBox} from 'element-plus'
import {t} from '@/locales'
import {catalogStyle,catalogSourceFiles,updateCatalogStyle} from '@/api/modules/partnerCatalog'
import {newRequestId} from '@/api/modules/partner'
import type {CatalogStyle} from '@/types/partnerCatalog'
import type {PartnerFile} from '@/types/partner'
import type {SelectionRow} from '@/types/partnerSelections'
import {cloneSelection,newSelectionRow} from '@/utils/partnerSelections'
import {partnerError} from '@/utils/partnerError'
import SelectionRowEditor from '../selection/SelectionRowEditor.vue'

const props=defineProps<{item:Pick<CatalogStyle,'sourceKind'|'recordId'|'rowId'|'supplierCode'|'supplierName'>;canReadCost:boolean}>()
const emit=defineEmits<{close:[];saved:[]}>()
const row=ref<SelectionRow>(),baseline=ref<SelectionRow>(),current=ref<CatalogStyle>(),busy=ref(false),error=ref('')
const libraryFiles=ref<PartnerFile[]>([]),libraryPage=ref(0),libraryLoaded=ref(0),libraryTotal=ref(0),libraryBusy=ref(false),libraryError=ref('')
const requestId=newRequestId()
const fields=['name','series','supplierItemCode','modelText','color','supplierColorIds','newSupplierColors','material','specification','moq','packaging','styleKey','configKey','unitPrice','currency','priceNote','notes','phoneModelIds','phoneScopeDeclaration','availablePhoneModelIds','unavailablePhoneModelIds','rangeRefs','pairings','rangeVersionAcknowledgements','supplyScopeNote','thickness','features','leadTimeNote','sampleFeeNote','priceOptions','priceUnit','moqQuantity','moqUnit','moqScope','moqNote','refs','sourcePosition','sameStyleKey','developmentEvidence','developmentStatus','developedOn'] as const
async function load(){busy.value=true;try{current.value=await catalogStyle(props.item.supplierCode,props.item);row.value={...newSelectionRow(),...cloneSelection(current.value as unknown as SelectionRow)};baseline.value=cloneSelection(row.value)}catch(e){error.value=partnerError(e)}finally{busy.value=false}}
async function loadLibrary(){
 if(libraryBusy.value)return
 libraryBusy.value=true;libraryError.value=''
 try{
  const page=await catalogSourceFiles(props.item.recordId,{supplierCode:props.item.supplierCode,page:libraryPage.value+1,size:100})
  libraryFiles.value=[...new Map([...libraryFiles.value,...page.content].map(file=>[file.id,file])).values()]
  libraryPage.value++;libraryLoaded.value+=page.content.length;libraryTotal.value=page.totalElements
 }catch(e){libraryError.value=partnerError(e)}finally{libraryBusy.value=false}
}
function openLibrary(){if(!libraryPage.value)void loadLibrary()}
function changes(){const result:Record<string,unknown>={};if(!row.value||!baseline.value)return result;for(const field of fields)if(JSON.stringify(row.value[field])!==JSON.stringify(baseline.value[field]))result[field]=row.value[field];return result as Partial<SelectionRow>}
async function close(){if(busy.value)return;if(Object.keys(changes()).length)try{await ElMessageBox.confirm(t('放弃本款尚未保存的内容？'),t('保留填写'),{confirmButtonText:t('放弃修改'),cancelButtonText:t('继续填写'),closeOnClickModal:false})}catch{return}emit('close')}
async function save(){if(busy.value||!row.value||!current.value)return;const changed=changes();if(!Object.keys(changed).length){emit('close');return}busy.value=true;error.value='';try{await updateCatalogStyle(props.item.supplierCode,current.value,{changes:changed,rowVersion:current.value.rowVersion,recordVersion:current.value.recordVersion,requestId});emit('saved')}catch(e){error.value=partnerError(e)}finally{busy.value=false}}
function beforeUnload(event:BeforeUnloadEvent){if(busy.value||Object.keys(changes()).length){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload);onBeforeUnmount(()=>window.removeEventListener('beforeunload',beforeUnload))
void load()
</script>
<template><el-dialog :model-value="true" :title="t('编辑供应商产品')" width="min(1020px,96vw)" top="20px" class="catalog-current-editor" append-to-body :close-on-click-modal="false" :before-close="close"><div v-loading="busy"><el-alert v-if="error" :title="error" type="error" :closable="false"/><p>{{t('同步当前资料；已确认报价、实收颜色与历史依据保留。')}}</p><SelectionRowEditor v-if="row" context="catalog" fixed-supplier :row="row" :files="current?.fileDetails||[]" :library-files="libraryFiles" :library-loading="libraryBusy" :suppliers="[{code:item.supplierCode,name:item.supplierName}]" :can-write="!busy" :can-read-cost="canReadCost" @library-open="openLibrary">
 <template #library-controls><div class="source-library-controls"><p>{{t('同一来源的图片与附件；选择不会改变其他行的关联。')}}</p><p v-if="libraryPage">{{t('已加载 {value0} / {value1} 份资料',{value0:libraryLoaded,value1:libraryTotal})}}</p><span v-if="libraryBusy" role="status">{{t('加载中')}}</span><div v-if="libraryError" role="alert">{{t('读取失败，请重试')}}：{{libraryError}} <el-button :disabled="libraryBusy" @click="loadLibrary">{{t('重试')}}</el-button></div><el-button v-else-if="libraryPage&&libraryLoaded<libraryTotal" :loading="libraryBusy" @click="loadLibrary">{{t('加载更多（已加载 {value0} / {value1}）',{value0:libraryLoaded,value1:libraryTotal})}}</el-button></div></template>
 </SelectionRowEditor></div><template #footer><el-button :disabled="busy" @click="close">{{t('取消')}}</el-button><el-button type="primary" :loading="busy" :disabled="!row?.name.trim()||busy" @click="save">{{t('保存产品资料')}}</el-button></template></el-dialog></template>
<style scoped>.source-library-controls{font-size:12px;line-height:1.6;margin-bottom:8px}.source-library-controls [role="alert"]{color:var(--el-color-danger);overflow-wrap:anywhere}.source-library-controls .el-button{max-width:100%;white-space:normal;line-height:1.5;height:auto}:global(.catalog-current-editor){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-bottom:20px}:global(.catalog-current-editor>.el-dialog__body){min-height:0;overflow-y:auto}:global(.catalog-current-editor>.el-dialog__footer){display:flex;justify-content:flex-end;gap:7px;flex-wrap:wrap}p{font-size:12px;color:var(--el-text-color-secondary);line-height:1.6;margin:0 0 9px}</style>
