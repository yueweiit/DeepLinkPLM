<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {newRequestId} from '@/api/modules/partner'
import * as selectionFiles from '@/api/modules/partnerSelections'
import type {PartnerFileContext} from '@/api/modules/partnerSelections'
import type {SelectionFileJob,SelectionStagedImage,SelectionStagedRow,SelectionUpload} from '@/types/partnerSelections'
import {partnerError} from '@/utils/partnerError'
import SelectionImportPhoto from './SelectionImportPhoto.vue'
import {selectionFileHistoryLabel,selectionFileStateLabel,selectionImportActionLabel,selectionImportDifferences,selectionImportPreviewFacts,selectionImportPriceFacts} from '@/utils/selectionImportPresentation'
import {supplierPriceSummary} from '@/utils/partnerPrice'
const props=withDefaults(defineProps<{modelValue:boolean;id:number;mode:'import'|'export';suppliers:{code:string;name:string}[];canWrite:boolean;context?:PartnerFileContext;supplierCode?:string}>(),{context:'selection'}),emit=defineEmits<{'update:modelValue':[boolean];changed:[];busy:[boolean]}>()
const fileApi=computed(()=>props.context==='catalog'?selectionFiles.catalogSourceFileClient:selectionFiles)
const sourceContext=computed(()=>props.context==='catalog'),destination=computed(()=>sourceContext.value?'供应商目录':'选品草稿')
const storagePrefix=computed(()=>sourceContext.value?'plm-catalog-source':'plm-selection')
type SheetMapping={name:string;supplierCode:string;selected:boolean;supplierConfirmed:boolean}
const supplierName=(code:string)=>props.suppliers.find(supplier=>supplier.code===code)?.name||'供应商资料待加载'
const targetSupplierName=computed(()=>props.supplierCode?supplierName(props.supplierCode):'请先选择供应商')
function sheetSupplierHint(name:string){const exact=name.trim().replace(/(?:选品|报价|目录)(?:表)?$/u,'').trim();const matches=props.suppliers.filter(supplier=>supplier.name.trim()===name.trim()||supplier.name.trim()===exact);return matches.length===1?matches[0]:undefined}
function selectSheet(sheet:SheetMapping,selected:boolean){sheet.selected=selected;if(sourceContext.value){sheet.supplierCode=selected?props.supplierCode||'':'';sheet.supplierConfirmed=false}}
const correctionFields=[{key:'name',label:'款式名称'},{key:'modelText',label:'型号原文'},{key:'color',label:'供方颜色原文'},{key:'currency',label:'币种（未知留空）'},{key:'notes',label:'核对说明'}] as const
const job=ref<SelectionFileJob>(),error=ref(''),busy=ref(false),progress=ref(''),sheetMappings=ref<SheetMapping[]>([]),rows=ref<SelectionStagedRow[]>([]),page=ref(1),total=ref(0),exportSupplier=ref(''),upload=ref<SelectionUpload>(),file=ref<File>(),edit=ref<SelectionStagedRow>()
const history=ref<SelectionFileJob[]>([]),imagesOpen=ref(false),images=ref<SelectionStagedImage[]>([]),imagePage=ref(1),imageTotal=ref(0),imageTargets=ref<Record<string,string>>({}),unassignedOnly=ref(true)
const archivedOriginal=ref<{attachmentId:number;name:string}>()
const key=computed(()=>`${storagePrefix.value}-${props.id}-${props.mode}-job`),running=computed(()=>['queued','processing'].includes(job.value?.status||'')),ready=computed(()=>job.value?.status==='ready'),stateLabel=computed(()=>parseUncertain.value?'解析结果待核实':selectionFileStateLabel(job.value?.status))
const steps=computed(()=>['上传原件','工作表与供应商','预览与核对',`保存${destination.value}`])
const completed=computed(()=>job.value?.status==='committed'),importStep=computed(()=>completed.value?3:!job.value?0:job.value.status==='awaiting_sheets'?1:2)
const selectedSheets=computed(()=>sheetMappings.value.filter(sheet=>sheet.selected))
const canParse=computed(()=>props.canWrite&&!busy.value&&!parseUncertain.value&&selectedSheets.value.length>0&&selectedSheets.value.every(sheet=>!!sheet.supplierCode&&(!sourceContext.value||sheet.supplierCode===props.supplierCode&&sheet.supplierConfirmed)))
const previewMappings=computed(()=>job.value?.selectedSheets?.length?job.value.selectedSheets:[...new Map(rows.value.map(row=>[row.sourceSheet,{name:row.sourceSheet,supplierCode:row.values.supplierCode||props.supplierCode||'',supplierConfirmed:false}])).values()])
const issueCount=computed(()=>Number(job.value?.stats?.errorCount||0)),unassignedCount=computed(()=>Number(job.value?.stats?.unassignedImageCount||0))
const imageCount=computed(()=>{const count=job.value?.stats?.imageCount;return typeof count==='number'&&Number.isSafeInteger(count)&&count>=0?count:undefined})
const canCommit=computed(()=>props.canWrite&&ready.value&&!busy.value&&!issueCount.value&&!unassignedCount.value)
const imageCorrectionVisible=computed(()=>props.mode==='import'&&ready.value&&(imagesOpen.value||unassignedCount.value>0))
const parseUncertain=ref(false),parseNotice=ref('')
let timer:ReturnType<typeof setTimeout>|undefined,generation=0
watch(busy,value=>emit('busy',value))
watch(()=>upload.value?.attachmentId,value=>{if(value)emit('changed')})
function stop(){if(timer)clearTimeout(timer)}
function remember(){if(job.value)localStorage.setItem(key.value,job.value.id)}
async function refresh(){
  if(!job.value)return false
  const seq=++generation
  error.value=''
  try{
    const data=props.mode==='import'?await fileApi.value.getSelectionImportJob(props.id,job.value.id):await fileApi.value.getSelectionExportJob(props.id,job.value.id)
    if(seq!==generation)return false
    job.value=data;parseUncertain.value=false;remember()
    if(data.sheets&&!sheetMappings.value.length)sheetMappings.value=data.sheets.map(sheet=>({name:sheet.name,supplierCode:'',selected:false,supplierConfirmed:false}))
    if(props.mode==='import'&&data.status==='ready'){await loadRows();if(unassignedCount.value>0)await loadImages()}
    return true
  }catch(e){if(seq===generation)error.value=partnerError(e);return false}
  finally{if(seq===generation){stop();if(props.modelValue&&running.value)timer=setTimeout(()=>void refresh(),2500)}}
}
watch(()=>[props.modelValue,props.id,props.mode,props.context],async()=>{stop();const seq=++generation;job.value=undefined;rows.value=[];history.value=[];sheetMappings.value=[];error.value='';imagesOpen.value=false;images.value=[];imageTargets.value={};edit.value=undefined;page.value=1;imagePage.value=1;upload.value=undefined;file.value=undefined;archivedOriginal.value=undefined;progress.value='';unassignedOnly.value=true;parseUncertain.value=false;parseNotice.value='';if(!props.modelValue)return;try{const data=await fileApi.value.selectionFileJobs(props.id);if(seq!==generation)return;history.value=data.content.filter(item=>item.kind===props.mode)}catch(e){if(seq!==generation)return;error.value=partnerError(e)}const id=localStorage.getItem(key.value);if(id){job.value={id,status:'loading'};await refresh()}},{immediate:true})
onBeforeUnmount(()=>{generation++;stop()})
watch(()=>props.supplierCode,()=>{if(sourceContext.value)for(const sheet of sheetMappings.value){sheet.selected=false;sheet.supplierCode='';sheet.supplierConfirmed=false}})
async function digest(blob:Blob){const bytes=await crypto.subtle.digest('SHA-256',await blob.arrayBuffer());return [...new Uint8Array(bytes)].map(value=>value.toString(16).padStart(2,'0')).join('')}
async function uploadFile(value:File){if(!props.canWrite||busy.value)return false;if(!/\.(xlsx|xls|pdf)$/i.test(value.name)){error.value='请选择XLSX、XLS或PDF文件；其他格式请先整理为XLSX。';return false}archivedOriginal.value=undefined;busy.value=true;file.value=value;error.value='';try{const resumeKey=`${storagePrefix.value}-${props.id}-upload`,fingerprint=JSON.stringify([value.name,value.size,value.lastModified]);let previous:{id:string;fingerprint:string}|undefined;try{previous=JSON.parse(localStorage.getItem(resumeKey)||'null')}catch{/* invalid local state never changes the server */}let state:SelectionUpload|undefined;if(previous?.fingerprint===fingerprint){try{state=await fileApi.value.getSelectionUpload(props.id,previous.id)}catch{/* Expired sessions are initialized again. */}}if(!state)state=await fileApi.value.createSelectionUpload(props.id,{fileName:value.name,fileSize:value.size,purpose:'cooperation_cost',requestId:newRequestId()});upload.value=state;localStorage.setItem(resumeKey,JSON.stringify({id:state.id,fingerprint}));for(let index=0;index<state.partCount;index++){const part=value.slice(index*state.chunkSize,Math.min(value.size,(index+1)*state.chunkSize));if(state.receivedParts.includes(index)){progress.value=`核对已上传内容 ${Math.round((index+1)/state.partCount*100)}%…`;const expected=state.partHashes?.[String(index)];if(!expected)throw new Error('当前服务未提供分片校验依据，不能安全续传。请等待服务更新，或明确另开上传会话。');if(await digest(part)!==expected)throw new Error('本地原件内容与已上传分片不一致，已停止续传；旧片保留，请核实原件或另开上传会话。');continue}progress.value=`正在上传 ${Math.round(index/state.partCount*100)}%（中断后可继续）`;state=await fileApi.value.putSelectionUploadPart(props.id,state.id,index,part,await digest(part));upload.value=state}progress.value='校验并登记原件…';state=await fileApi.value.completeSelectionUpload(props.id,state.id);upload.value=state;if(!state.attachmentId)throw new Error('原件尚未完成登记，请重试校验。');if(!/\.xlsx$/i.test(value.name)){archivedOriginal.value={attachmentId:state.attachmentId,name:value.name};job.value=undefined;rows.value=[];total.value=0;sheetMappings.value=[];localStorage.removeItem(key.value);localStorage.removeItem(resumeKey);progress.value='';return false}job.value=await fileApi.value.createSelectionImportJob(props.id,state.attachmentId,`upload-${state.id}`);localStorage.removeItem(resumeKey);sheetMappings.value=[];remember();await refresh();progress.value=''}catch(e){error.value=partnerError(e);progress.value='已上传内容保留。重新选择同一原件可继续。'}finally{busy.value=false}return false}
function newUploadSession(){localStorage.removeItem(`${storagePrefix.value}-${props.id}-upload`);upload.value=undefined;file.value=undefined;progress.value='旧会话未删除；重新选择原件将建立独立上传。';error.value=''}
async function parse(){
  if(!job.value||!canParse.value||job.value.status!=='awaiting_sheets')return
  const documentId=props.id,jobId=job.value.id,scope=generation
  busy.value=true;error.value='';parseNotice.value=''
  try{
    job.value=await fileApi.value.parseSelectionImportJob(documentId,jobId,{sheets:selectedSheets.value.map(({name,supplierCode,supplierConfirmed})=>({name,supplierCode,...(sourceContext.value?{supplierConfirmed}: {})})),requestId:newRequestId()})
    remember();await refresh()
  }catch(e){
    if(scope!==generation)return
    parseUncertain.value=true
    const reconciled=await refresh()
    if(documentId!==props.id||jobId!==job.value?.id||!props.modelValue)return
    if(!reconciled){parseUncertain.value=true;error.value='解析结果待核实。请刷新核实进度，暂不能重新发起解析，避免重复处理。'}
    else if(job.value.status==='awaiting_sheets'){error.value=partnerError(e)+'；已核对后台尚未开始处理，可以再次预览。'}
    else{parseNotice.value='刚才没有收到处理回应，已按后台实际进度恢复。'}
  }finally{busy.value=false}
}
async function loadRows(){if(!job.value)return;const seq=generation;try{const data=await fileApi.value.selectionImportJobRows(props.id,job.value.id,page.value,20);if(seq!==generation)return;rows.value=data.content;total.value=data.totalElements}catch(e){if(seq===generation)error.value=partnerError(e)}}
async function saveRow(){if(!job.value||!edit.value||!props.canWrite||!ready.value)return;busy.value=true;error.value='';try{await fileApi.value.updateSelectionImportRow(props.id,job.value.id,edit.value,edit.value.values);edit.value=undefined;await refresh()}catch(e){error.value=partnerError(e)}finally{busy.value=false}}
async function commit(){if(!job.value||!canCommit.value)return;busy.value=true;error.value='';try{await fileApi.value.commitSelectionImportJob(props.id,job.value.id,`commit-${job.value.id}`);emit('changed');await refresh()}catch(e){error.value=partnerError(e)}finally{busy.value=false}}
async function startExport(){busy.value=true;error.value='';try{job.value=await fileApi.value.createSelectionExportJob(props.id,{requestId:newRequestId(),supplierCode:exportSupplier.value||undefined});remember();await refresh()}catch(e){error.value=partnerError(e)}finally{busy.value=false}}
async function download(){if(!job.value)return;busy.value=true;error.value='';try{await fileApi.value.downloadSelectionExportJob(props.id,job.value.id)}catch(e){error.value=partnerError(e)}finally{busy.value=false}}
async function restoreJob(value:SelectionFileJob){job.value=value;rows.value=[];sheetMappings.value=[];imagesOpen.value=false;page.value=1;remember();await refresh()}
async function retry(){if(!job.value||!props.canWrite||busy.value||job.value.status!=='failed')return;busy.value=true;try{job.value=await fileApi.value.retrySelectionFileJob(props.id,props.mode,job.value.id);await refresh()}catch(e){error.value=partnerError(e)}finally{busy.value=false}}
async function loadImages(){if(!job.value)return;imagesOpen.value=true;const seq=generation;try{const data=await fileApi.value.selectionImportImages(props.id,job.value.id,{page:imagePage.value,size:10,unassignedOnly:unassignedOnly.value});if(seq!==generation)return;images.value=data.content;imageTotal.value=data.totalElements}catch(e){if(seq===generation)error.value=partnerError(e)}}
async function assignImage(image:SelectionStagedImage){if(!job.value||!imageTargets.value[image.id]||!props.canWrite||!ready.value||busy.value)return;busy.value=true;try{job.value=await fileApi.value.assignSelectionImportImage(props.id,job.value.id,image.id,imageTargets.value[image.id]);await loadImages();await loadRows()}catch(e){error.value=partnerError(e)}finally{busy.value=false}}
function reset(){stop();generation++;job.value=undefined;sheetMappings.value=[];rows.value=[];imagesOpen.value=false;images.value=[];imageTargets.value={};edit.value=undefined;file.value=undefined;archivedOriginal.value=undefined;upload.value=undefined;localStorage.removeItem(key.value);error.value='';progress.value='';parseUncertain.value=false;parseNotice.value='';page.value=1}
</script>

<template>
  <el-dialog :model-value="modelValue" class="selection-file-dialog" top="16px" :title="mode==='import'?(sourceContext?uiText('导入供应商资料'):uiText('导入我方选品资料')):(sourceContext?uiText('导出供应商资料'):uiText('导出选品模板'))" width="min(1020px,96vw)" :close-on-click-modal="false" :show-close="!busy" :close-on-press-escape="!busy" append-to-body @update:model-value="emit('update:modelValue',$event)">
    <ol v-if="mode==='import'" class="import-steps" :aria-label='uiText("导入步骤")'>
      <li v-for="(label,index) in steps" :key="label" :class="{active:index===importStep,passed:index<importStep}" :aria-current="index===importStep?'step':undefined"><span>{{index+1}}</span>{{label}}</li>
    </ol>
    <div class="file-tasks">
      <slot name="source-files"/>
      <div v-if="sourceContext&&mode==='import'" class="source-destination"><span>{{ uiText("本次目标供应商") }}</span><strong>{{targetSupplierName}}</strong></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false"/>
      <el-alert v-if="parseNotice" :title="parseNotice" type="info" :closable="false"/>
      <template v-if="mode==='import'&&!job">
        <h3>{{sourceContext?uiText('上传供应商原件或整理后的Excel'):uiText('导入已挑选的Excel或继续模板往返')}}</h3>
        <p v-if="!sourceContext" class="task-help">{{ uiText("这里只处理我方已选资料及原模板往返；供应商完整报价或目录请先到产品目录导入，再选款式和具体型号。") }}<a href="#/partner-products?sourceManager=1">{{ uiText("打开供应商目录导入") }}</a></p>
        <p class="task-help">{{ uiText("XLSX可预览导入；XLS、PDF仅保留来源原件，不自动解析。下一步再选择整理后XLSX的工作表和供应商，不用先拆文件。") }}</p>
        <el-alert v-if="archivedOriginal" class="original-archive-notice" :title='uiText("原件已保留（未解析）")' :description="archivedOriginal.name+uiText('；请再选择整理后的XLSX预览导入。')" type="success" :closable="false" show-icon/>
        <el-upload class="original-upload" drag :show-file-list="false" accept=".xlsx,.xls,.pdf" :disabled="busy||!canWrite" :before-upload="uploadFile">
          <strong>{{file?.name||uiText('拖入XLSX、XLS或PDF原件，或点击选择')}}</strong><span>{{progress||uiText('分片上传并校验原件；按报价资料权限保留。')}}</span>
        </el-upload>
        <el-button v-if="upload&&error" :disabled="busy" @click="newUploadSession">{{ uiText("另开上传（保留旧内容）") }}</el-button>
      </template>
      <template v-if="mode==='export'">
        <p class="task-help">{{ uiText("每家供应商一个工作表，保留图片及型号、颜色下拉。未实际收样的批次号留空，不冻结行列。") }}</p>
        <div class="task-toolbar"><el-select v-model="exportSupplier" filterable clearable :placeholder='uiText("全部关联供应商，每家一页")'><el-option v-for="supplier in suppliers" :key="supplier.code" :value="supplier.code" :label="supplier.name"/></el-select><el-button type="primary" :disabled="busy||running" @click="startExport">{{ uiText("生成新版模板") }}</el-button></div>
      </template>
      <details v-if="history.length&&!running" class="task-history"><summary>{{ uiText("继续上次") }}{{mode==='import'?uiText('导入'):uiText('导出')}}（{{history.length}}）</summary><el-button v-for="(item,index) in history" :key="item.id" text :disabled="busy" @click="restoreJob(item)">{{uiText(selectionFileHistoryLabel(item,index))}}</el-button></details>
      <template v-if="job">
        <div class="job-summary"><el-tag :type="completed?'success':job.status==='failed'?'danger':'info'">{{uiText(stateLabel)}}</el-tag><strong v-if="job.stats&&(ready||completed||Number(job.stats.rowCount)>0||Number(imageCount)>0)">{{job.stats.rowCount||0}}{{ uiText(" 条款式") }}<template v-if="imageCount!==undefined"> · {{imageCount}}{{ uiText(" 张图片") }}</template></strong><el-button v-if="running||job.status==='failed'||parseUncertain" link :disabled="busy" @click="refresh">{{parseUncertain?uiText('刷新核实进度'):uiText('刷新进度')}}</el-button><el-button v-if="job.status==='failed'" link type="primary" :disabled="busy||!canWrite" @click="retry">{{ uiText("保留原件重试") }}</el-button></div>
        <p v-if="job.error||job.message" class="task-error" role="alert">{{job.error||job.message}}</p>
        <template v-if="mode==='import'&&job.status==='awaiting_sheets'">
          <h3>{{sourceContext?uiText('选择本次导入的工作表'):uiText('选择工作表，分别指定供应商')}}</h3><p class="task-help">{{ uiText("只处理勾选页，其余页面保留在原件中。") }}{{sourceContext?uiText('每个选中页都要核对实际归属；工作表名称仅供提示。其他供应商请另建来源资料。'):uiText('供应商从已有档案选择，不会按工作表名新建。')}}</p>
          <div class="sheet-mappings"><article v-for="sheet in sheetMappings" :key="sheet.name"><div class="sheet-entry"><el-checkbox :model-value="sheet.selected" :disabled="busy||!canWrite" @change="selectSheet(sheet,Boolean($event))">{{sheet.name}}</el-checkbox><template v-if="sourceContext"><p v-if="sheet.selected&&sheetSupplierHint(sheet.name)&&sheetSupplierHint(sheet.name)?.code!==supplierCode" class="sheet-supplier-warning">{{ uiText("页名提示：") }}{{sheetSupplierHint(sheet.name)?.name}}{{ uiText("，与本次目标不同。请核对原件的实际归属，不会自动关联。") }}</p><p v-else-if="sheet.selected&&!sheetSupplierHint(sheet.name)" class="task-help">{{ uiText("页名未对应已有供应商，请核对原件实际属于本次目标。") }}</p><el-checkbox v-if="sheet.selected" v-model="sheet.supplierConfirmed" class="sheet-confirmation" :disabled="busy||!canWrite">{{ uiText("已核对本页实际属于本次目标供应商") }}</el-checkbox></template></div><span v-if="sourceContext" class="sheet-state" :class="{'needs-confirmation':sheet.selected&&!sheet.supplierConfirmed}">{{!sheet.selected?uiText('未导入'):sheet.supplierConfirmed?uiText('将导入'):uiText('待确认归属')}}</span><el-select v-else v-model="sheet.supplierCode" filterable clearable :disabled="!sheet.selected||busy||!canWrite" :aria-label="sheet.name+uiText('的供应商')" :placeholder='uiText("选择供应商")'><el-option v-for="supplier in suppliers" :key="supplier.code" :value="supplier.code" :label="supplier.name"/></el-select></article></div>
          <p class="task-help">{{ uiText("已选 ") }}{{selectedSheets.length}}{{ uiText(" 页") }}<template v-if="sourceContext&&selectedSheets.some(sheet=>!sheet.supplierConfirmed)">{{ uiText("，请逐页确认实际归属后再预览。") }}</template><template v-else-if="selectedSheets.some(sheet=>!sheet.supplierCode)">{{ uiText("，请补齐对应供应商。") }}</template></p>
        </template>
        <div v-if="running" class="processing-state" role="status"><strong>{{ uiText("正在后台") }}{{mode==='import'?uiText('读取款式和图片'):uiText('生成模板')}}…</strong><p>{{ uiText("可以关闭此窗口，稍后从“继续上次") }}{{mode==='import'?uiText('导入'):uiText('导出')}}{{ uiText("”恢复。") }}</p></div>
        <template v-if="mode==='import'&&ready">
          <div class="preview-heading"><h3>{{sourceContext?uiText('核对供应商候选资料'):uiText('核对本次选品内容')}}</h3><span v-if="issueCount||unassignedCount" class="task-error">{{issueCount}}{{ uiText(" 处资料问题") }}<template v-if="unassignedCount"> · {{unassignedCount}}{{ uiText(" 张图片待归属") }}</template></span><span v-else class="success-note">{{sourceContext?uiText('可以保存到供应商目录'):uiText('可以保存为选品草稿')}}</span></div>
          <p class="task-help">{{ uiText("已有资料只显示本次差异，缺行或空白不代表删除。") }}{{sourceContext?uiText('保存仅表示供应商提供这些款式，尚未加入我方选品，不创建产品或样品批次。'):uiText('保存草稿后仍可继续选品，不产生收样、质检或上架事实。')}}</p>
          <section v-if="sourceContext&&previewMappings.length" class="preview-sheet-mappings" :aria-label='uiText("本次工作表归属")'><h4>{{job.selectedSheets?.length?uiText('本次工作表归属'):uiText('当前预览页归属')}}</h4><ul><li v-for="mapping in previewMappings" :key="mapping.name"><strong>{{mapping.name}}</strong><span>→ {{supplierName(mapping.supplierCode)}}</span><small>{{mapping.supplierConfirmed?uiText('工程已核对'):uiText('旧任务未记录逐页确认，请核对现有预览')}}</small></li></ul></section>
          <div class="task-preview">
            <article v-for="row in rows" :key="row.rowId" class="staged-row">
              <header><strong>{{row.values.name||uiText('待补款名')}}</strong><el-tag size="small" :type="row.action==='conflict'?'warning':'info'">{{uiText(selectionImportActionLabel(row.action))}}</el-tag><el-button v-if="canWrite" link type="primary" :disabled="busy" @click="edit=JSON.parse(JSON.stringify(row))">{{row.messages.length?uiText('修正问题'):uiText('修改本行')}}</el-button><small>{{row.sourceSheet}}{{ uiText(" · 第") }}{{row.rowNumber}}{{ uiText("行") }}</small></header>
              <div class="staged-content">
                <div v-if="row.values._images?.length" class="import-images"><SelectionImportPhoto v-for="image in row.values._images" :key="image.id" :id="id" :context="context" :job-id="job.id" :image-id="image.id" :name="image.name"/></div>
                <dl class="row-business">
                  <div><dt>{{ uiText("手机型号") }}</dt><dd class="preview-model">{{row.values.modelText||uiText('未填写')}}</dd></div>
                  <div v-if="row.values.supplierCode"><dt>{{ uiText("供应商") }}</dt><dd>{{suppliers.find(supplier=>supplier.code===row.values.supplierCode)?.name||uiText('名称待核对')}}</dd></div>
                  <div v-if="row.values.color"><dt>{{ uiText("供方颜色") }}</dt><dd>{{row.values.color}}</dd></div>
                  <div v-if="row.values.material" data-preview-field="material"><dt>{{ uiText("材质") }}</dt><dd>{{row.values.material}}</dd></div>
                  <div v-if="row.values.specification" data-preview-field="specification"><dt>{{ uiText("配置") }}</dt><dd>{{row.values.specification}}</dd></div>
                  <div v-if="row.values.moq"><dt>{{ uiText("起订条件原文") }}</dt><dd>{{row.values.moq}}</dd></div>
                  <div v-if="!row.values.priceOptions?.length&&row.values.unitPrice!==undefined&&row.values.unitPrice!==''"><dt>{{ uiText("单价") }}</dt><dd>{{supplierPriceSummary(row.values)}}</dd></div>
                  <div v-if="row.values.sampleQty!==undefined"><dt>{{ uiText("拟拿样") }}</dt><dd>{{row.values.sampleQty}}{{ uiText("（本行合计）") }}</dd></div>
                  <div v-for="fact in selectionImportPreviewFacts(row.values)" :key="fact.key" class="preview-extra-fact" :data-preview-field="fact.key"><dt>{{uiText(String((fact.label) ?? ''))}}</dt><dd><details v-if="fact.expand" class="preview-source-text"><summary>{{ uiText("展开原文") }}</summary><p>{{fact.text}}</p></details><span v-else>{{fact.text}}</span></dd></div>
                </dl>
              </div>
              <section v-if="row.values.priceOptions?.length" class="preview-price-options" :aria-label='uiText("本款配置与报价")'><h4>{{ uiText("配置与报价") }}</h4><article v-for="(option,index) in row.values.priceOptions" :key="index"><header><strong>{{option.label||uiText('报价选项 {value0}',{value0:(index+1)})}}</strong><span>{{supplierPriceSummary(option)}}</span></header><dl><div v-for="fact in selectionImportPriceFacts(option)" :key="fact.key"><dt>{{uiText(String((fact.label) ?? ''))}}</dt><dd><details v-if="fact.expand" class="preview-source-text"><summary>{{ uiText("展开原文") }}</summary><p>{{fact.text}}</p></details><span v-else>{{fact.text}}</span></dd></div></dl></article></section>
              <p v-for="message in row.messages" :key="message" class="task-error">{{message}}</p>
              <details v-if="selectionImportDifferences(row,suppliers).length" class="business-differences"><summary>{{ uiText("查看 ") }}{{selectionImportDifferences(row,suppliers).length}}{{ uiText(" 项业务差异") }}</summary><div v-for="change in selectionImportDifferences(row,suppliers)" :key="change.key"><strong>{{uiText(String((change.label) ?? ''))}}</strong><span><small>{{ uiText("原来") }}</small>{{change.previous}}</span><span><small>{{ uiText("本次") }}</small>{{change.next}}</span></div></details>
            </article>
          </div>
          <el-pagination v-model:current-page="page" :page-size="20" :total="total" :pager-count="5" layout="total,prev,pager,next" @current-change="loadRows"/>
          <section v-if="imageCorrectionVisible" class="image-assignment">
            <div class="preview-heading"><h3>{{unassignedCount?uiText('请确认未归属的图片'):uiText('复查图片归属')}}</h3><el-button v-if="!unassignedCount" link @click="imagesOpen=false">{{ uiText("收起") }}</el-button></div>
            <p class="task-help">{{ uiText("只修正有问题的图片，已对应的图片不用重复确认。目标从本页款式选择；其他行先翻上方预览页。") }}</p>
            <el-checkbox v-model="unassignedOnly" @change="imagePage=1;loadImages()">{{ uiText("只看未归属图片") }}</el-checkbox>
            <article v-for="image in images" :key="image.id"><SelectionImportPhoto :id="id" :context="context" :job-id="job.id" :image-id="image.id" :name="image.name"/><div class="image-target"><span>{{image.sourceSheet}}{{ uiText(" · 原第") }}{{image.sourceRow}}{{ uiText("行") }}</span><el-select v-model="imageTargets[image.id]" filterable :placeholder='uiText("选择这张图对应的款式")'><el-option v-for="row in rows" :key="row.rowId" :value="row.rowId" :label="(row.values.name||uiText('待补款名'))+' · '+row.sourceSheet+uiText('第')+row.rowNumber+uiText('行')"/></el-select><el-button :disabled="!imageTargets[image.id]||busy||!canWrite" @click="assignImage(image)">{{ uiText("保存图片归属") }}</el-button></div></article>
            <p v-if="!images.length" class="task-help">{{ uiText("当前没有需要处理的图片。") }}</p><el-pagination v-model:current-page="imagePage" :total="imageTotal" :page-size="10" :pager-count="5" layout="total,prev,pager,next" @current-change="loadImages"/>
          </section>
          <el-button v-else-if="job.stats?.imageCount" class="recheck-images" link :disabled="busy" @click="unassignedOnly=false;imagePage=1;loadImages()">{{ uiText("需要时复查图片对应款式") }}</el-button>
        </template>
        <div v-if="mode==='import'&&completed" class="completed-state" role="status"><h3>{{ uiText("已保存到") }}{{destination}}</h3><p>{{sourceContext?uiText('原件和图片已保留。请回供应商目录选择需要的款式和具体型号，再加入我方选品。'):uiText('本次原件和图片已保留，可回到工作台继续选款。未自动创建收样、质检通过或上架记录。')}}</p></div>
        <div v-if="mode==='export'&&ready" class="completed-state"><h3>{{ uiText("模板已准备好") }}</h3><p>{{ uiText("点击下方“下载XLSX”，编辑后可作为原件重新导入核对差异。") }}</p></div>
        <details class="task-diagnostics"><summary>{{ uiText("处理信息（需要协助时使用）") }}</summary><label>{{ uiText("任务编号") }}<input :value="job.id" readonly :aria-label='uiText("任务编号（供排查）")' @focus="($event.target as HTMLInputElement).select()"/></label></details>
      </template>
    </div>
    <template #footer>
      <div class="task-footer">
        <el-button :disabled="busy" @click="emit('update:modelValue',false)">{{completed?(sourceContext?uiText('完成，返回供应商目录'):uiText('完成，返回选品')):uiText('稍后继续')}}</el-button>
        <el-button v-if="job&&!running&&!busy" @click="reset">{{mode==='export'?uiText('重新选择导出范围'):completed?uiText('导入另一份原件'):uiText('换一份原件')}}</el-button>
        <el-button v-if="mode==='import'&&job?.status==='awaiting_sheets'" type="primary" :disabled="!canParse" @click="parse">{{ uiText("下一步：预览所选页") }}</el-button>
        <el-button v-if="mode==='import'&&ready&&canWrite" type="primary" :loading="busy" :disabled="!canCommit" @click="commit">{{ uiText("保存") }}{{destination}}</el-button>
        <el-button v-if="mode==='export'&&ready" type="primary" :loading="busy" @click="download">{{ uiText("下载XLSX") }}</el-button>
        <span v-if="mode==='import'&&ready&&(issueCount||unassignedCount)" class="task-error">{{ uiText("请先修正上方问题，再保存。") }}</span>
      </div>
    </template>
    <el-dialog :model-value="!!edit" :title='uiText("修改本行预览（保存草稿前）")' width="min(700px,94vw)" append-to-body @update:model-value="!$event&&(edit=undefined)">
      <el-form v-if="edit" label-position="top"><el-form-item v-for="field in correctionFields" :key="field.key" :label="uiText(field.label)"><el-input v-model="edit.values[field.key]" type="textarea" autosize/></el-form-item></el-form>
      <template #footer><el-button @click="edit=undefined">{{ uiText("取消") }}</el-button><el-button type="primary" :loading="busy" :disabled="!canWrite" @click="saveRow">{{ uiText("保存本行修正") }}</el-button></template>
    </el-dialog>
  </el-dialog>
</template>
<style scoped>
.preview-extra-fact dd{min-width:0;white-space:pre-wrap}.preview-source-text{margin:0;min-width:0}.preview-source-text summary{cursor:pointer;color:#34597d;font-size:12px}.preview-source-text p{margin:4px 0 0;white-space:pre-wrap}.preview-price-options{margin-top:8px;border-top:1px solid #e7edf5;padding-top:5px;min-width:0}.preview-price-options h4{font-size:13px;margin:0 0 5px}.preview-price-options>article{padding:6px 0;border-top:1px solid #edf1f6;min-width:0}.preview-price-options>article:first-of-type{border-top:0}.preview-price-options header{display:flex;align-items:baseline;gap:5px 12px;flex-wrap:wrap;min-width:0}.preview-price-options header strong{font-size:13px;overflow-wrap:anywhere}.preview-price-options header>span{color:#284d7c;overflow-wrap:anywhere}.preview-price-options dl{margin:3px 0 0;display:flex;gap:3px 16px;flex-wrap:wrap}.preview-price-options dl>div{display:flex;gap:6px;min-width:0}.preview-price-options dt{color:#7c899a;flex:none}.preview-price-options dd{margin:0;min-width:0;overflow-wrap:anywhere;white-space:pre-wrap}.business-differences>div>span{white-space:pre-wrap}
:global(.selection-file-dialog){max-height:calc(100dvh - 32px);display:flex;flex-direction:column;margin-bottom:16px}
:global(.selection-file-dialog > .el-dialog__body){min-height:0;overflow:hidden;display:flex;flex-direction:column}
:global(.selection-file-dialog > .el-dialog__header),:global(.selection-file-dialog > .el-dialog__footer){flex:none}
.import-steps{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:7px;list-style:none;padding:0;margin:0 0 12px}
.import-steps li{display:flex;align-items:center;gap:6px;color:#6e7d91;font-size:13px;min-width:0;overflow-wrap:anywhere}
.import-steps li>span{width:22px;height:22px;flex:none;display:grid;place-items:center;border-radius:50%;background:#edf1f6}
.import-steps .active{color:#1e5c9d;font-weight:600}.import-steps .active>span{background:#e6f0fc}.import-steps .passed>span{background:#e9f5ed;color:#428354}
.file-tasks{flex:1;max-height:calc(100dvh - 240px);overflow-y:auto;min-height:120px;min-width:0;font-size:13px;padding-right:4px;scrollbar-gutter:stable}
.file-tasks h3{font-size:15px;margin:6px 0}.file-tasks p{line-height:1.6;overflow-wrap:anywhere}.task-help{color:#6b7c90;margin:5px 0 10px}
.original-upload{margin:12px 0}.original-upload :deep(.el-upload),.original-upload :deep(.el-upload-dragger){width:100%}.original-upload :deep(.el-upload-dragger){padding:28px 15px}.original-upload strong,.original-upload span{display:block;white-space:normal;overflow-wrap:anywhere}.original-upload strong{font-size:15px;color:#294d79}.original-upload span{margin-top:9px;color:#6b7c90}
.task-toolbar,.job-summary,.preview-heading,.task-footer{display:flex;align-items:center;flex-wrap:wrap;gap:8px}.task-toolbar{margin:10px 0}.task-toolbar>.el-select{min-width:180px;flex:1}.job-summary{margin-bottom:10px}.job-summary strong{font-size:13px}.preview-heading{justify-content:space-between;margin-top:6px}
.task-history{border-top:1px solid #e7edf5;margin-top:12px;padding:9px 0}.task-history>summary,.business-differences>summary,.task-diagnostics>summary{color:#57728f;cursor:pointer}.task-history>.el-button{display:block;text-align:left;white-space:normal;height:auto;min-height:30px;line-height:1.6;padding:4px}
.sheet-mappings article{display:flex;align-items:center;gap:12px;border-bottom:1px solid #e8edf4;padding:9px 0}.sheet-mappings article>.el-checkbox{flex:1;height:auto;min-width:0}.sheet-mappings article>.el-select{width:260px;max-width:55%}
.source-destination{display:flex;align-items:baseline;gap:8px;flex-wrap:wrap;padding:7px 10px;background:#f2f6fb;border-radius:4px;margin-bottom:8px}.source-destination>span{color:#6b7c90;font-size:12px}.sheet-entry{flex:1;min-width:0}.sheet-entry>.el-checkbox{height:auto;margin-right:0;max-width:100%}.sheet-entry>.sheet-confirmation{display:flex;font-size:12px;margin-top:7px}.sheet-entry p{margin:5px 0;font-size:12px}.sheet-state{color:#7c899a;font-size:12px;flex:none}.sheet-state.needs-confirmation,.sheet-supplier-warning{color:#a46420}.preview-sheet-mappings{padding:7px 10px;background:#f6f8fb;border-radius:4px;margin-bottom:9px}.preview-sheet-mappings h4{font-size:13px;margin:0 0 4px}.preview-sheet-mappings ul{list-style:none;margin:0;padding:0}.preview-sheet-mappings li{display:flex;gap:4px 10px;flex-wrap:wrap;overflow-wrap:anywhere;padding:3px 0}.preview-sheet-mappings small{color:#7c899a;font-size:12px}
.processing-state,.completed-state{padding:18px 0}.completed-state h3,.success-note{color:#39814d}.task-preview{border:1px solid #e0e8f2;border-radius:5px}.staged-row{padding:10px;border-bottom:1px solid #e0e8f2}.staged-row:last-child{border:0}.staged-row>header{display:flex;align-items:center;gap:7px;flex-wrap:wrap}.staged-row>header>strong{font-size:14px;color:#2b3f58;overflow-wrap:anywhere}.staged-row>header>small{margin-left:auto;color:#79889b;overflow-wrap:anywhere}
.staged-content{display:flex;gap:12px;align-items:flex-start;margin-top:7px;min-width:0}.import-images{display:flex;gap:6px;flex-wrap:wrap;flex:none;max-width:38%}.row-business{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:4px 15px;flex:1;min-width:0;margin:0}.row-business>div{display:flex;gap:7px;min-width:0}.row-business dt{color:#7c899a;flex:none}.row-business dd{margin:0;overflow-wrap:anywhere}.row-business>div:first-child{grid-column:1/-1}.row-business .preview-model{font-size:15px;line-height:1.5;font-weight:600;color:#1d416c}
.task-error{color:#a46420}.business-differences{margin-top:8px}.business-differences>div{display:grid;grid-template-columns:100px 1fr 1fr;gap:9px;padding:6px 0;border-bottom:1px solid #edf1f6;overflow-wrap:anywhere}.business-differences small{display:block;color:#7c899a}.business-differences strong{font-weight:500}
.image-assignment{border-top:1px solid #dae5f3;margin-top:15px;padding-top:8px}.image-assignment>article{display:flex;gap:12px;align-items:flex-start;border-bottom:1px solid #e8edf4;padding:10px 0}.image-target{flex:1;display:flex;align-items:center;gap:8px;flex-wrap:wrap;min-width:0}.image-target>span{width:100%;color:#6b7c90}.image-target>.el-select{flex:1;min-width:180px}.recheck-images{margin-top:8px}
.task-diagnostics{margin-top:14px;font-size:12px}.task-diagnostics label{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:7px}.task-diagnostics input{min-width:0;width:300px;max-width:100%;padding:5px;border:1px solid #dce4ee;border-radius:4px;color:#6b7c90;background:#f8fafc}.task-footer{justify-content:flex-end;gap:7px}.task-footer>.task-error{width:100%;font-size:12px}
.file-tasks :deep(.el-pagination){flex-wrap:wrap;gap:2px;margin-top:10px}.file-tasks :deep(.el-button+.el-button),.task-footer :deep(.el-button+.el-button){margin-left:0}.file-tasks :deep(.el-checkbox__label){white-space:normal;overflow-wrap:anywhere}
@media(max-width:700px){.import-steps{gap:4px}.import-steps li{font-size:12px;gap:4px}.file-tasks{max-height:calc(100dvh - 270px)}.staged-content{flex-direction:column;gap:7px}.import-images{max-width:100%}.row-business{width:100%}.sheet-mappings article{align-items:flex-start}.business-differences>div{grid-template-columns:85px 1fr}.business-differences>div>span:last-child{grid-column:2}.staged-row>header>small{width:100%;margin-left:0}}
@media(max-width:480px){.import-steps{grid-template-columns:repeat(2,minmax(0,1fr))}.row-business{grid-template-columns:1fr}.sheet-mappings article{flex-direction:column;gap:7px}.sheet-mappings article>.el-select{width:100%;max-width:100%}.task-footer>.el-button{margin:0}.image-assignment>article{flex-direction:column}.image-target>.el-select{min-width:130px}}
</style>
