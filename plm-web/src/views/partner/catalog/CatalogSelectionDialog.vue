<script setup lang="ts">
import { t as uiText } from '@/locales'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { addCatalogSelection, previewCatalogSelection } from '@/api/modules/partnerCatalog'
import { newRequestId } from '@/api/modules/partner'
import type { CatalogAddition, CatalogAdditionPreview, CatalogAdditionResult, CatalogSelectionItem, CatalogStyle } from '@/types/partnerCatalog'
import { partnerError } from '@/utils/partnerError'
import { sampleColorOptions as colorOptions } from '@/utils/partnerSelections'
import { unavailablePhone } from '@/components/PhoneModelMultiSelect/selection'
import PartnerPhoto from '../PartnerPhoto.vue'
import SampleColorSelect from '../selection/SampleColorSelect.vue'
import { catalogAllowedPhoneIds, catalogIdentity, catalogKey } from './catalogPresentation'

const props = defineProps<{ modelValue:boolean; supplierCode:string; supplierName:string; items:CatalogStyle[]; canWrite:boolean; targetSelectionId?:number; targetVersion?:number }>()
const emit = defineEmits<{ 'update:modelValue':[value:boolean]; added:[result:CatalogAdditionResult]; busy:[value:boolean] }>()
const busy=ref(false),unknown=ref(false),closing=ref(false),error=ref(''),requestId=ref(''),preview=ref<CatalogAdditionPreview>()
const sampleColors=ref<Record<string,string[]>>({}),excluded=ref(new Set<string>()),commonColors=ref<string[]>([]),reselected=ref<Record<string,boolean>>({})
const rowElements=new Map<string,HTMLElement>()
let generation=0,pendingCommand:CatalogAddition|undefined
const sourceContext=computed(()=>JSON.stringify([props.supplierCode,props.targetSelectionId,props.targetVersion,props.items.map(row=>({
 ...catalogIdentity(row),supplierCode:row.supplierCode,catalogStyleId:row.catalogStyleId,phoneScopeMode:row.phoneScopeMode,
 phoneModelIds:[...new Set(row.phoneModelIds)].sort((a,b)=>a-b),unavailablePhoneModelIds:[...new Set(row.unavailablePhoneModelIds||[])].sort((a,b)=>a-b),
 phones:row.phones.map(phone=>[phone.phoneModelId,unavailablePhone(phone)]).sort((a,b)=>Number(a[0])-Number(b[0]))
})).sort((a,b)=>catalogKey(a).localeCompare(catalogKey(b)))]))
const defaultColors=(row:{color?:string;sampleColors?:string[]})=>row.sampleColors??colorOptions({color:row.color})
const displayedColors=(row:CatalogStyle)=>sampleColors.value[catalogKey(row)]??defaultColors(row)
const commonColorOptions=computed(()=>[...new Set(props.items.flatMap(colorOptions))])
const selectedItems=computed<CatalogSelectionItem[]>(()=>props.items.filter(row=>!excluded.value.has(catalogKey(row))).map(row=>({ ...catalogIdentity(row),phoneModelIds:[],selectionIntent:'style' as const,...(reselected.value[catalogKey(row)]?{reselectCancelled:true}:{}),...(sampleColors.value[catalogKey(row)]!==undefined?{sampleColors:sampleColors.value[catalogKey(row)]}:{}) })))
const valid=computed(()=>props.canWrite&&selectedItems.value.length>0)
const hasUnsavedChoice=computed(()=>Object.keys(sampleColors.value).length>0||Object.values(reselected.value).some(Boolean)||excluded.value.size>0||commonColors.value.length>0)
const validationIssues=computed(()=>preview.value&&!preview.value.canAdd?[...new Set([...(preview.value.messages||[]),...(preview.value.items||[]).flatMap(item=>(item.messages||[]).map(message=>`${item.name}：${message}`))])]:[])
const issueItems=computed(()=>preview.value&&!preview.value.canAdd?(preview.value.items||[]).filter(item=>item.messages?.length&&props.items.some(row=>catalogKey(row)===catalogKey(item))):[])
const reselectItems=computed(()=>(preview.value?.items||[]).filter(item=>item.requiresReselect===true&&selectedItems.value.some(selected=>catalogKey(selected)===catalogKey(item))))
const photo=(row:CatalogStyle)=>row.fileDetails?.find(file=>file.type.startsWith('image/'))
const suppliedCount=(row:CatalogStyle)=>catalogAllowedPhoneIds(row).filter(id=>{const phone=row.phones.find(phone=>phone.phoneModelId===id);return phone&&!unavailablePhone(phone)}).length
const incompleteScope=(row:CatalogStyle)=>!!row.phoneMatchIssues?.length||!!row.phoneMatchSummary?.unresolvedCount
function payload():Omit<CatalogAddition,'requestId'|'previewToken'>{return {supplierCode:props.supplierCode,targetMode:'supplier_directory',items:selectedItems.value,...(props.targetSelectionId!==undefined?{targetSelectionId:props.targetSelectionId}:{}),...(props.targetVersion!==undefined?{targetVersion:props.targetVersion}:{})}}
watch(()=>props.modelValue,open=>{generation++;if(unknown.value&&pendingCommand)return;error.value='';preview.value=undefined;pendingCommand=undefined;if(!open)return;sampleColors.value={};excluded.value=new Set();commonColors.value=[];reselected.value={};requestId.value=newRequestId()})
watch([sampleColors,excluded],()=>{if(unknown.value)return;reselected.value={};preview.value=undefined;pendingCommand=undefined;requestId.value=newRequestId();error.value=''},{deep:true})
watch(sourceContext,()=>{generation++;if(!unknown.value){reselected.value={};preview.value=undefined;pendingCommand=undefined;requestId.value=newRequestId()}})
watch(reselected,()=>{if(unknown.value)return;preview.value=undefined;pendingCommand=undefined;requestId.value=newRequestId();error.value=''},{deep:true})
watch([busy,unknown],()=>emit('busy',busy.value||unknown.value),{flush:'sync'})
onBeforeUnmount(()=>{generation++;emit('busy',false)})
function changeColors(row:CatalogStyle,values:string[]){if(busy.value||unknown.value||!props.canWrite)return;sampleColors.value[catalogKey(row)]=[...values]}
function applyColors(){if(busy.value||unknown.value||!props.canWrite)return;for(const row of props.items)if(!excluded.value.has(catalogKey(row))&&sampleColors.value[catalogKey(row)]===undefined)sampleColors.value[catalogKey(row)]=[...commonColors.value]}
function toggleStyle(row:CatalogStyle){if(busy.value||unknown.value||!props.canWrite)return;const key=catalogKey(row);if(excluded.value.has(key))excluded.value.delete(key);else excluded.value.add(key)}
async function close(done?:()=>void){
 if(busy.value||closing.value)return
 if(unknown.value){ElMessage.warning(uiText('请先核对本次保存结果，避免重复提交。'));return}
 const serial=generation;closing.value=true
 try{if(hasUnsavedChoice.value)await ElMessageBox.confirm(uiText('本次款式或选品颜色调整还未保存，确定放弃并关闭吗？'),uiText('保留本次选品'),{confirmButtonText:uiText('放弃并关闭'),cancelButtonText:uiText('继续选品'),closeOnClickModal:false});if(serial!==generation||!props.modelValue)return;done?.();emit('update:modelValue',false)}catch{/* Keep the entered colours when discard is cancelled. */}finally{closing.value=false}
}
function payloadFingerprint(input:ReturnType<typeof payload>){return JSON.stringify({...input,items:[...input.items].sort((a,b)=>catalogKey(a).localeCompare(catalogKey(b)))})}
function rememberRow(key:string,element:unknown){if(element instanceof HTMLElement)rowElements.set(key,element);else rowElements.delete(key)}
async function locateIssue(item:CatalogSelectionItem){await nextTick();const element=rowElements.get(catalogKey(item));element?.scrollIntoView({block:'nearest'});element?.focus({preventScroll:true})}
async function reselectCurrent(){if(busy.value||unknown.value||!props.canWrite||!valid.value||!reselectItems.value.length)return;for(const item of reselectItems.value)reselected.value[catalogKey(item)]=true;await nextTick();await submit()}
function resultUnknown(cause:unknown){const failure=cause as {code?:string;response?:{status?:number}};return ['ECONNABORTED','ETIMEDOUT','ERR_NETWORK'].includes(failure?.code||'')||Number(failure?.response?.status)>=500}
async function submit(){
 if(!props.canWrite||busy.value||(!unknown.value&&!valid.value))return
 if(pendingCommand&&(pendingCommand.supplierCode!==props.supplierCode||pendingCommand.targetSelectionId!==props.targetSelectionId||pendingCommand.targetVersion!==props.targetVersion)){error.value='待核对的保存属于原供应商或原选品目录，请返回原页面核对。';return}
 const serial=generation,input=payload(),fingerprint=payloadFingerprint(input);busy.value=true;error.value=''
 try{
  if(!pendingCommand){if(!preview.value?.canAdd||!preview.value.previewToken){const checked=await previewCatalogSelection(input);if(!props.modelValue)return;if(serial!==generation||fingerprint!==payloadFingerprint(payload())){error.value='来源或选品目录已变化，请重新核对后保存。';return}preview.value=checked;if(!checked.canAdd){if(!validationIssues.value.length)error.value='本次选品未通过核对，请检查款式后重试。';return}if(!checked.previewToken){error.value='预览未返回保存凭证，请重试；本次填写已保留。';return}}pendingCommand=JSON.parse(JSON.stringify({...input,requestId:requestId.value,previewToken:preview.value.previewToken})) as CatalogAddition}
  const result=await addCatalogSelection(pendingCommand)
  if(!props.modelValue)return
  if(serial!==generation){error.value='来源或选品目录已变化，请返回原目录核对保存结果。';return}
  unknown.value=false;pendingCommand=undefined;emit('added',result);emit('update:modelValue',false);ElMessage.success(uiText('已加入选品目录；实际收样另行登记'))
 }catch(cause){if(props.modelValue){if(serial!==generation){error.value='来源或选品目录已变化，请返回原目录核对保存结果。';return}unknown.value=!!pendingCommand&&resultUnknown(cause);if(unknown.value)error.value='保存结果待核对，请点击“核对保存结果”；会沿用本次资料，不重复加入。';else{pendingCommand=undefined;preview.value=undefined;error.value=partnerError(cause)}}}finally{busy.value=false}
}
</script>

<template>
 <el-dialog :model-value="modelValue" class="catalog-selection-dialog-shell" :title="uiText('加入选品目录')" width="min(1000px,96vw)" top="20px" append-to-body :close-on-click-modal="false" :close-on-press-escape="!busy&&!unknown" :show-close="!busy&&!unknown" :before-close="close">
  <div class="catalog-selection-dialog">
   <p class="selection-intro">{{supplierName||supplierCode}}{{uiText(' · 已勾选 ')}}{{items.length}}{{uiText(' 款')}}</p>
   <p class="selection-note">{{uiText('这里只选款式，自动包含该款全部可供型号并随供方目录更新。实际收到的型号、颜色和数量在登记收样时确认。')}}</p>
   <details v-if="items.length>1" class="selection-batch"><summary>{{uiText('批量调整选品颜色（选填）')}}</summary><div class="selection-batch-controls"><SampleColorSelect v-model="commonColors" :options="commonColorOptions" :label="uiText('批量选品颜色')" :disabled="busy||unknown||!canWrite"/><el-button :disabled="busy||unknown||!canWrite" @click="applyColors">{{uiText('应用颜色')}}</el-button><small>{{uiText('保留单款已调整的颜色')}}</small></div></details>
   <div class="selection-table-heading"><span>{{uiText('款式')}}</span><span>{{uiText('供方型号范围')}}</span><span>{{uiText('选品颜色（可调整）')}}</span><span>{{uiText('操作')}}</span></div>
   <article v-for="row in items" :key="catalogKey(row)" :ref="element=>rememberRow(catalogKey(row),element)" tabindex="-1" class="selection-model-row" :class="{'is-excluded':excluded.has(catalogKey(row))}">
    <header><PartnerPhoto v-if="photo(row)" :id="photo(row)!.id"/><span v-else class="selection-photo-placeholder">{{uiText('暂无图片')}}</span><div><strong>{{row.name}}</strong><small v-if="row.supplierItemCode">{{row.supplierItemCode}}</small></div></header>
    <div class="selection-scope"><span v-if="row.phoneScopeMode!=='unresolved'&&row.phoneScopeMode!=='unspecified'&&suppliedCount(row)">{{incompleteScope(row)?uiText('已关联可供型号'):uiText('全部可供型号')}} · {{suppliedCount(row)}}{{uiText('个')}}<small>{{uiText('随供方目录更新')}}</small><small v-if="incompleteScope(row)">{{uiText('部分供方型号待核对')}}</small></span><span v-else>{{uiText('供方型号待补')}}<small>{{uiText('可先加入款式，收样前再核对')}}</small></span><small v-if="row.modelText">{{row.modelText}}</small></div>
    <div class="selection-row-colors"><label>{{uiText('选品颜色（可调整）')}}</label><SampleColorSelect :model-value="displayedColors(row)" :options="colorOptions(row)" :label="row.name+uiText('选品颜色')" :disabled="busy||unknown||!canWrite||excluded.has(catalogKey(row))" @update:model-value="changeColors(row,$event)"/></div>
    <el-button link :disabled="busy||unknown||!canWrite" @click="toggleStyle(row)">{{excluded.has(catalogKey(row))?uiText('恢复本款'):uiText('本次不加入')}}</el-button>
   </article>
   <p class="selection-note">{{targetSelectionId?uiText('加入原选品目录，同款自动复用。'):uiText('加入本供应商的选品目录，同款自动复用。')}}{{uiText('此操作不生成实物样品。')}}</p>
  </div>
  <template #footer><div v-if="error||validationIssues.length" class="selection-errors selection-save-error" role="alert"><p v-if="error">{{uiText(error)}}</p><p v-for="message in validationIssues" :key="message" class="selection-issue">{{message}}</p><el-button v-for="item in issueItems" :key="catalogKey(item)" link size="small" @click="locateIssue(item)">{{uiText('定位款式')}} · {{item.name}}</el-button></div><div v-if="reselectItems.length||Object.values(reselected).some(Boolean)" class="selection-reselection"><span>{{uiText('以前的选品已取消，重新加入将保留原历史')}}</span><el-button v-if="reselectItems.length" :disabled="busy||unknown||!canWrite||!valid" type="primary" plain @click="reselectCurrent">{{uiText('重新加入本次款式')}}</el-button></div><strong class="selection-total">{{uiText('已选 {value0} 款',{value0:selectedItems.length})}}</strong><el-button :disabled="busy||closing||unknown" @click="close()">{{uiText('取消')}}</el-button><el-button type="primary" :disabled="!canWrite||busy||closing||(!unknown&&!valid)" :loading="busy" @click="submit">{{unknown?uiText('核对保存结果'):uiText('加入选品目录')}}</el-button></template>
 </el-dialog>
</template>

<style scoped>
:global(.catalog-selection-dialog-shell){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-bottom:20px;padding:0;font-size:12px;--el-component-size:28px}
:global(.catalog-selection-dialog-shell>.el-dialog__header){padding:16px 20px 0;flex:none}
:global(.catalog-selection-dialog-shell .el-dialog__title){font-size:16px;font-weight:600}
:global(.catalog-selection-dialog-shell>.el-dialog__body){padding:10px 20px 16px;min-height:0;overflow:auto}
:global(.catalog-selection-dialog-shell>.el-dialog__footer){display:flex;justify-content:flex-end;align-items:center;gap:8px;flex-wrap:wrap;padding:12px 20px;background:var(--el-fill-color-light);border-top:1px solid var(--el-border-color-lighter);flex:none}
:global(.catalog-selection-dialog-shell>.el-dialog__footer>.el-button){margin:0;font-size:12px;min-height:28px;height:auto;white-space:normal}
.catalog-selection-dialog{container-type:inline-size;min-width:0;font-size:12px;color:var(--el-text-color-regular)}
.selection-intro,.selection-note{margin:0 0 10px;line-height:1.6;overflow-wrap:anywhere}.selection-note,.selection-intro{color:var(--el-text-color-secondary)}
.selection-table-heading,.selection-model-row{display:grid;grid-template-columns:minmax(0,1.1fr) minmax(0,1.1fr) minmax(0,1fr) 70px;gap:12px;align-items:center;padding:10px 8px}
.selection-table-heading{background:var(--el-fill-color-light);font-weight:600}.selection-model-row{border-bottom:1px solid var(--el-border-color-lighter);padding-block:14px;min-width:0}.selection-model-row.is-excluded{opacity:.65}
.selection-model-row header{display:flex;align-items:center;gap:10px;min-width:0}.selection-model-row header>div{min-width:0;overflow-wrap:anywhere}.selection-model-row strong{font-size:12px}
.selection-photo-placeholder,.selection-model-row :deep(.partner-photo){width:40px;height:42px;flex:none;border-radius:4px}.selection-photo-placeholder{display:grid;place-items:center;background:var(--el-fill-color-light);font-size:11px;text-align:center}
.selection-model-row small{display:block;line-height:1.6;color:var(--el-text-color-secondary);margin-top:3px;overflow-wrap:anywhere}.selection-scope{min-width:0;overflow-wrap:anywhere;line-height:1.6}.selection-row-colors{min-width:0}.selection-row-colors>label{display:none}
.catalog-selection-dialog :deep(.el-button),.catalog-selection-dialog :deep(.el-input__inner){font-size:12px}.catalog-selection-dialog :deep(.el-button){margin:0;white-space:normal;overflow-wrap:anywhere;height:auto;min-height:28px}
.selection-batch{margin-bottom:10px;padding:10px;background:var(--el-fill-color-light);border-radius:5px}.selection-batch summary{cursor:pointer;font-weight:600}.selection-batch-controls{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding-top:8px}.selection-batch-controls>.sample-color-select{flex:1;min-width:180px}.selection-batch-controls small{color:var(--el-text-color-secondary)}
.selection-save-error{flex-basis:100%;max-height:120px;overflow:auto;text-align:left}.selection-save-error p{margin:0 0 3px}.selection-reselection{display:flex;flex-basis:100%;gap:8px;flex-wrap:wrap;font-size:12px;text-align:left;align-items:center}.selection-reselection>span{flex:1;min-width:200px}.selection-errors{color:var(--el-color-danger);overflow-wrap:anywhere}.selection-total{margin-right:auto;font-size:12px}
@container(max-width:720px){.selection-table-heading{display:none}.selection-model-row{grid-template-columns:minmax(0,1fr) 80px;gap:8px}.selection-model-row header{grid-column:1/-1}.selection-scope,.selection-row-colors{grid-column:1;padding-left:50px}.selection-row-colors>label{display:block;margin-bottom:4px}.selection-model-row>.el-button{grid-column:2;grid-row:2/4}}
</style>
