<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,onBeforeUnmount,reactive,ref,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {useRouter} from 'vue-router'
import {newRequestId} from '@/api/modules/partner'
import {previewSelectionReceipts,saveSelectionReceipts} from '@/api/modules/partnerSelections'
import type {SelectionReceiptInput,SelectionReceiptInputRow,SelectionReceiptPreview,SelectionReceiptPreviewRow,SelectionReceiptResult,SelectionRow} from '@/types/partnerSelections'
import {partnerError} from '@/utils/partnerError'
import {useUserStore} from '@/stores/user'
import PartnerPhoto from '../PartnerPhoto.vue'
import {sampleIdentityCandidates,sampleIdentities} from '@/api/modules/sampleManagement'
import type {SampleIdentity,SampleLabel} from '@/types/sampleManagement'
import SampleLabelPrint from '../samples/SampleLabelPrint.vue'

const props=defineProps<{id:number;version:number;rows:SelectionRow[];from:string;supplierShipmentId?:number}>()
const emit=defineEmits<{close:[];saved:[result:SelectionReceiptResult];busy:[value:boolean]}>()
const user=useUserStore(),actor=String(user.profile?.userId||''),actorChanged=ref(false)
const router=useRouter(),busy=ref(false),loading=ref(true),error=ref(''),unknown=ref(false),phase=ref<'edit'|'saved'>('edit'),result=ref<SelectionReceiptResult>(),preview=ref<SelectionReceiptPreview>(),showIssues=ref(false)
const shared=reactive({date:'',location:'',ownerName:''}),lines=ref<SelectionReceiptInputRow[]>([]),metadata=ref(new Map<string,SelectionReceiptPreviewRow>()),requestId=ref<string>(newRequestId())
type BulkSpec={id:string;colorName:string;qty?:number}
const blankBulkSpec=():BulkSpec=>({id:newRequestId(),colorName:''})
const bulkSpecs=ref<BulkSpec[]>([blankBulkSpec()]),checkedLines=ref(new Set<string>())
const hasBulkValues=computed(()=>bulkSpecs.value.some(spec=>spec.colorName.trim()||spec.qty!=null))
const showBulk=computed(()=>lines.value.length>1),selectedBulkLines=computed(()=>lines.value.filter(line=>checkedLines.value.has(line.clientRowId)))
const allBulkChecked=computed(()=>!!lines.value.length&&selectedBulkLines.value.length===lines.value.length),someBulkChecked=computed(()=>!!selectedBulkLines.value.length&&!allBulkChecked.value)
const bulkDisabled=computed(()=>busy.value||loading.value||unknown.value||actorChanged.value||phase.value!=='edit')
function checkBulkLine(id:string,checked:boolean){if(bulkDisabled.value)return;if(checked)checkedLines.value.add(id);else checkedLines.value.delete(id)}
function checkAllBulk(checked:boolean){if(!bulkDisabled.value)checkedLines.value=new Set(checked?lines.value.map(line=>line.clientRowId):[])}
function selectInitialLines(){checkedLines.value=new Set(lines.value.map(line=>line.clientRowId))}
function addBulkSpec(){if(!bulkDisabled.value)bulkSpecs.value.push(blankBulkSpec())}
function removeBulkSpec(id:string){if(!bulkDisabled.value&&bulkSpecs.value.length>1)bulkSpecs.value=bulkSpecs.value.filter(spec=>spec.id!==id)}
const colorKey=(name:string)=>name.trim().toLowerCase()
const scopeKey=(line:SelectionReceiptInputRow)=>JSON.stringify([line.rowId,line.phoneModelId??null,line.rowVersion])
function applyBulk(){
 if(bulkDisabled.value||String(user.profile?.userId||'')!==actor)return
 const targets=selectedBulkLines.value,specs=bulkSpecs.value.map(spec=>({...spec,colorName:spec.colorName.trim()})).filter(spec=>spec.colorName||spec.qty!=null)
 if(!targets.length){ElMessage.warning(uiText('请先勾选要填写的收样规格'));return}
 if(!specs.length){ElMessage.warning(uiText('请填写颜色或每条数量'));return}
 if(specs.some(spec=>spec.qty!=null&&(!Number.isSafeInteger(spec.qty)||Number(spec.qty)<=0))){ElMessage.warning(uiText('每条数量请填写大于0的整数'));return}
 if(specs.some(spec=>spec.colorName.length>255||/[\u0000-\u001f\u007f-\u009f]/.test(spec.colorName))){ElMessage.warning(uiText('请填写有效颜色名称，最多255个字'));return}
 if(specs.length===1){
  const spec=specs[0]!
  for(const line of targets){if(spec.colorName){if(spec.colorName===colorValue(line).trim())line.colorName=spec.colorName;else colorInput(line,spec.colorName)}if(spec.qty!=null)line.qty=spec.qty}
  ElMessage.success(uiText('已应用到{value0}条收样规格',{value0:(targets.length)}));return
 }
 if(specs.some(spec=>!spec.colorName)){ElMessage.warning(uiText('多种颜色请逐行填写颜色名称'));return}
 if(new Set(specs.map(spec=>colorKey(spec.colorName))).size!==specs.length){ElMessage.warning(uiText('批量颜色重复，请合并为一行'));return}
 const groups=new Map<string,SelectionReceiptInputRow[]>()
 for(const line of targets){const key=scopeKey(line);if(!groups.has(key))groups.set(key,[]);groups.get(key)!.push(line)}
 const plan:{line?:SelectionReceiptInputRow;anchor:SelectionReceiptInputRow;spec:BulkSpec;matched:boolean}[]=[]
 for(const [key,selected] of groups){
  const anchor=selected[0]!
  if(!anchor.phoneModelId){ElMessage.warning(uiText('请先选择勾选款式的实收型号'));return}
  const scoped=lines.value.filter(line=>scopeKey(line)===key),reserved=new Set<string>(),missing:BulkSpec[]=[]
  for(const spec of specs){
   const matches=scoped.filter(line=>colorKey(colorValue(line))===colorKey(spec.colorName))
   if(matches.some(line=>!checkedLines.value.has(line.clientRowId))){ElMessage.warning(uiText('该款已有未勾选的同色规格，请勾选后应用'));return}
   if(matches.length>1){ElMessage.warning(uiText('该款有多条同名颜色规格，请先逐条核对'));return}
   const match=matches[0]
   if(match){reserved.add(match.clientRowId);plan.push({line:match,anchor,spec,matched:true})}else missing.push(spec)
  }
  for(const spec of missing){
   const blank=selected.find(line=>!reserved.has(line.clientRowId)&&!line.colorId&&!colorValue(line).trim())
   if(blank)reserved.add(blank.clientRowId)
   plan.push({line:blank,anchor,spec,matched:false})
  }
 }
 for(const item of plan){
  const line=item.line||createLine(item.anchor)
  if(!item.line){const after=lines.value.map(scopeKey).lastIndexOf(scopeKey(item.anchor));lines.value.splice(after+1,0,line);checkedLines.value.add(line.clientRowId)}
  if(item.matched)line.colorName=item.spec.colorName;else colorInput(line,item.spec.colorName)
  if(item.spec.qty!=null)line.qty=item.spec.qty
 }
 ElMessage.success(uiText('已应用到{value0}款型号，共{value1}条收样规格',{value0:(groups.size),value1:(plan.length)}))
}
const identityOptions=ref(new Map<string,SampleIdentity[]>()),exactIdentityIds=ref(new Map<string,Set<number>>()),identityLoading=ref(new Set<string>()),identityReview=ref(new Map<string,string>()),printOpen=ref(false)
const printLabels=computed<SampleLabel[]>(()=>[...new Map((result.value?.batches||[]).filter(b=>b.sampleIdentityId&&b.sampleCode).map(b=>[b.sampleIdentityId,{sampleIdentityId:b.sampleIdentityId!,sampleCode:b.sampleCode!,name:b.name,supplierName:b.supplierName,phoneName:b.phoneName,colorName:b.colorName}])).values()])
let identityEpoch=0
const identityTickets=new Map<string,number>()
async function identitiesFor(line:SelectionReceiptInputRow,keyword=''){
 if(!line.phoneModelId||!line.colorId)return
 const ticket=++identityEpoch,auth=user.token;identityTickets.set(line.clientRowId,ticket);identityLoading.value.add(line.clientRowId)
 try{if(keyword.trim()){const data=await sampleIdentities({keyword:keyword.trim(),page:1,size:30});if(auth===user.token&&!actorChanged.value&&ticket===identityTickets.get(line.clientRowId))identityOptions.value.set(line.clientRowId,data.content)}else{const data=await sampleIdentityCandidates({sourceType:'catalog',selectionId:props.id,selectionRowId:line.rowId,phoneModelId:line.phoneModelId,colorId:line.colorId});if(auth!==user.token||actorChanged.value||ticket!==identityTickets.get(line.clientRowId))return;identityOptions.value.set(line.clientRowId,data.content);exactIdentityIds.value.set(line.clientRowId,new Set(data.content.map(i=>i.sampleIdentityId)));identityReview.value.set(line.clientRowId,data.requiresReview?data.reason||'请核对并明确选择真实同物样品码':'');if(!line.sampleIdentityId&&!data.requiresReview&&data.content.length===1)line.sampleIdentityId=data.content[0]!.sampleIdentityId}}
 catch(e){if(auth===user.token&&!actorChanged.value&&ticket===identityTickets.get(line.clientRowId))error.value=e instanceof Error?e.message:'样品码候选读取失败，可重试'}finally{if(ticket===identityTickets.get(line.clientRowId))identityLoading.value.delete(line.clientRowId)}
}
function clearColorIdentity(line:SelectionReceiptInputRow){line.sampleIdentityId=undefined;line.identityReason=undefined;identityOptions.value.delete(line.clientRowId);exactIdentityIds.value.delete(line.clientRowId);identityReview.value.delete(line.clientRowId);identityLoading.value.delete(line.clientRowId);identityTickets.set(line.clientRowId,++identityEpoch)}
function colorInput(line:SelectionReceiptInputRow,value:string){line.colorName=value;line.colorId=undefined;clearColorIdentity(line)}
type ColorSuggestion={value:string;id:number;name:string;description:string}
function colorSuggestions(line:SelectionReceiptInputRow){return(query:string,done:(items:ColorSuggestion[])=>void)=>{
 const colors=info(line)?.colors||[],keyword=query.trim().toLocaleLowerCase(),counts=new Map<string,number>()
 for(const color of colors){const name=color.name.trim().toLocaleLowerCase();counts.set(name,(counts.get(name)||0)+1)}
 done(colors.filter(color=>!keyword||[color.name,color.code,color.supplierColorCode].some(value=>String(value||'').toLocaleLowerCase().includes(keyword))).map(color=>({value:color.name,id:color.id,name:color.name,description:[color.supplierColorCode?'色号 '+color.supplierColorCode:(counts.get(color.name.trim().toLocaleLowerCase())||0)>1?'记录 '+(color.code||color.id):'',color.styleKey&&'款式 '+color.styleKey,color.configKey&&'配置 '+color.configKey].filter(Boolean).join(' · ')})))
}}
async function selectColor(line:SelectionReceiptInputRow,color:Record<string,unknown>){clearColorIdentity(line);line.colorName=String(color.name??color.value??'');const id=Number(color.id);line.colorId=Number.isSafeInteger(id)&&id>0?id:undefined;if(line.colorId)await identitiesFor(line)}
function needsIdentityReason(line:SelectionReceiptInputRow){return !!line.sampleIdentityId&&!exactIdentityIds.value.get(line.clientRowId)?.has(line.sampleIdentityId)}
function viewIdentity(id:number){void router.push({path:'/samples',query:{identityId:id,from:props.from}});emit('close')}
const pendingKey=`plm:selection-receipt:${actor}:${props.id}${props.supplierShipmentId?':shipment:'+props.supplierShipmentId:''}`
const clearPending=()=>{try{sessionStorage.removeItem(pendingKey)}catch{/* Browser storage may be unavailable. */}}
let pendingPayload:SelectionReceiptInput|undefined,sequence=0,initializing=true
const payload=():SelectionReceiptInput=>({version:props.version,requestId:requestId.value,...shared,...(props.supplierShipmentId?{supplierShipmentId:props.supplierShipmentId}:{}),rows:lines.value.map(line=>({...line}))})
const info=(line:SelectionReceiptInputRow)=>metadata.value.get(line.clientRowId)
const colorValue=(line:SelectionReceiptInputRow)=>line.colorName??info(line)?.colors.find(color=>color.id===line.colorId)?.name??''
const phoneLabel=(line:SelectionReceiptInputRow)=>{const phone=info(line)?.phones?.find(phone=>phone.phoneModelId===line.phoneModelId);return phone?.displayName||phone?.modelName||'请选择实收型号'}
const photos=(line:SelectionReceiptInputRow)=>info(line)?.refs||[]
const styleName=(line:SelectionReceiptInputRow)=>info(line)?.name||props.rows.find(row=>row.rowId===line.rowId)?.name||'收样款式'
const styleLabel=(line:SelectionReceiptInputRow)=>info(line)?.catalogStyleLabel||props.rows.find(row=>row.rowId===line.rowId)?.catalogStyleLabel||props.rows.find(row=>row.rowId===line.rowId)?.catalogStyleCode||''
const totalQty=computed(()=>lines.value.reduce((total,line)=>total+(Number(line.qty)||0),0))
function setBusy(value:boolean){busy.value=value;emit('busy',!actorChanged.value&&(value||unknown.value))}
function invalidate(){if(initializing||unknown.value||actorChanged.value||phase.value==='saved')return;preview.value=undefined;showIssues.value=false;error.value='';requestId.value=newRequestId();phase.value='edit'}
watch([shared,lines],invalidate,{deep:true})
function acceptOptions(data:SelectionReceiptPreview){preview.value=data;for(const item of data.rows)metadata.value.set(item.clientRowId,{...item,phones:item.phones||[],colors:item.colors||[],refs:item.refs||[],messages:item.messages||[]})}
async function options(){const seq=++sequence;loading.value=true;error.value='';try{const data=await previewSelectionReceipts(props.id,payload());if(seq!==sequence)return;acceptOptions(data);for(const line of lines.value){const phones=info(line)?.phones||[];if(!line.phoneModelId&&phones.length===1)line.phoneModelId=phones[0].phoneModelId}}catch(e){if(seq===sequence)error.value=partnerError(e)}finally{if(seq===sequence)loading.value=false}}
async function initialize(){if(!actor){loading.value=false;initializing=false;actorChanged.value=true;error.value='登录身份尚未就绪，请重新进入收样。';return}lines.value=props.rows.map(row=>({clientRowId:newRequestId(),rowId:row.rowId,rowVersion:row.rowVersion||0}));selectInitialLines();try{const stored=sessionStorage.getItem(pendingKey);if(stored){const pending=JSON.parse(stored) as {actor:string;input:SelectionReceiptInput;metadata:SelectionReceiptPreviewRow[]};if(pending.actor!==actor)throw new Error('收样身份不符');pendingPayload=pending.input;requestId.value=pending.input.requestId;Object.assign(shared,{date:pending.input.date,location:pending.input.location,ownerName:pending.input.ownerName});lines.value=pending.input.rows;selectInitialLines();metadata.value=new Map(pending.metadata.map(row=>[row.clientRowId,row]));unknown.value=true;phase.value='edit';loading.value=false;error.value='上次保存结果尚未核对，请先核对本次保存结果。';emit('busy',true);initializing=false;return}}catch{clearPending()}await options();initializing=false;preview.value=undefined;for(const line of lines.value)if(line.phoneModelId&&line.colorId)void identitiesFor(line)}
watch(()=>user.profile?.userId,value=>{if(String(value||'')!==actor){actorChanged.value=true;unknown.value=false;lines.value=[];checkedLines.value.clear();bulkSpecs.value=[blankBulkSpec()];metadata.value=new Map();Object.assign(shared,{date:'',location:'',ownerName:''});error.value='登录账号已改变，请重新进入收样。';emit('busy',false)}})
void initialize()
function createLine(line:SelectionReceiptInputRow):SelectionReceiptInputRow{const clientRowId=newRequestId(),source=info(line);if(source)metadata.value.set(clientRowId,{...source,clientRowId,messages:[],canSave:false});return{clientRowId,rowId:line.rowId,rowVersion:line.rowVersion,phoneModelId:line.phoneModelId}}
function addLine(line:SelectionReceiptInputRow){lines.value.push(createLine(line))}
function removeLine(line:SelectionReceiptInputRow){checkedLines.value.delete(line.clientRowId);lines.value=lines.value.filter(item=>item.clientRowId!==line.clientRowId)}
async function phoneChanged(line:SelectionReceiptInputRow){line.colorId=undefined;clearColorIdentity(line);await options();preview.value=undefined}
function persistPending(input:SelectionReceiptInput){pendingPayload=input;try{sessionStorage.setItem(pendingKey,JSON.stringify({actor,input,metadata:[...metadata.value.values()]}))}catch{/* The in-memory request is retained if browser storage is unavailable. */}}
function isUnknown(e:unknown){const failure=e as {code?:string;response?:{status?:number}};return ['ECONNABORTED','ETIMEDOUT','ERR_NETWORK'].includes(failure?.code||'')||Number(failure?.response?.status)>=500}
async function save(){if(actorChanged.value||String(user.profile?.userId||'')!==actor){error.value='登录账号已改变，请重新进入收样。';return}if(busy.value||loading.value||!lines.value.length)return;setBusy(true);error.value='';if(lines.value.some(line=>needsIdentityReason(line)&&!line.identityReason?.trim())){error.value='选择其他来源的已有样品码时，请填写同物核对原因';setBusy(false);return}const input=pendingPayload||payload();try{if(!unknown.value){const checked=await previewSelectionReceipts(props.id,input);if(actorChanged.value)return;acceptOptions(checked);showIssues.value=true;if(!checked.canSave)return}persistPending(input);const saved=await saveSelectionReceipts(props.id,input);if(actorChanged.value){clearPending();return}unknown.value=false;clearPending();result.value=saved;phase.value='saved';emit('saved',saved)}catch(e){if(actorChanged.value)return;unknown.value=!!pendingPayload&&isUnknown(e);if(unknown.value)error.value='保存结果待核对。请保持本次资料，点击“核对本次保存结果”，不会另建一笔收样。';else{pendingPayload=undefined;clearPending();error.value=partnerError(e)}}finally{setBusy(false)}}

function close(){if(actorChanged.value){emit('close');return}if(busy.value)return;if(unknown.value){ElMessage.warning(uiText('请先核对本次保存结果，避免重复登记。'));return}emit('close')}
function viewBatch(id:number){void router.push({path:'/samples',query:{id,from:props.from}});emit('close')}
onBeforeUnmount(()=>{sequence++;emit('busy',false)})
</script>

<template>
 <el-dialog :model-value="true" :title='uiText("登记实际收样")' class="selection-receipt-dialog" top="20px" width="min(1120px,96vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!busy&&!unknown" :before-close="close">
  <el-alert v-if="actorChanged" :title="error" type="error" :closable="false"/>
  <section v-show="!actorChanged&&phase!=='saved'" class="receipt-form">
   <p class="receipt-intro">{{ uiText("只登记实际收到的样品。日期、位置和保管人填写一次；各款填写实收型号、颜色和数量。") }}</p>
   <el-alert v-if="error" :title="error" type="error" :closable="false"/>
   <el-form label-position="top" class="receipt-common" :disabled="busy||loading||unknown">
    <el-form-item :label='uiText("实际入库日期 *")'><el-date-picker v-model="shared.date" type="date" value-format="YYYY-MM-DD" :placeholder='uiText("选择实际日期")' :aria-label='uiText("实际入库日期")'/></el-form-item>
    <el-form-item :label='uiText("存放位置 *")'><el-input v-model="shared.location" :placeholder='uiText("实际存放在哪里")' :aria-label='uiText("存放位置")'/></el-form-item>
    <el-form-item :label='uiText("保管人 *")'><el-input v-model="shared.ownerName" :placeholder='uiText("实际保管人")' :aria-label='uiText("保管人")'/></el-form-item>
   </el-form>
   <div v-if="showIssues&&preview?.messages?.length" class="receipt-errors" role="alert"><p v-for="message in preview.messages" :key="message">{{message}}</p></div>
   <div v-if="showBulk" class="receipt-bulk">
    <div class="receipt-bulk-scope"><el-checkbox :model-value="allBulkChecked" :indeterminate="someBulkChecked" :disabled="bulkDisabled" :aria-label='uiText("全选本次收样规格")' @change="checkAllBulk(Boolean($event))">{{ uiText("全选") }}</el-checkbox><span>{{ uiText("已勾选 ") }}{{selectedBulkLines.length}}{{ uiText(" 条") }}</span></div>
    <div class="receipt-bulk-specs">
     <div v-for="(spec,index) in bulkSpecs" :key="spec.id" class="receipt-bulk-line">
      <label class="receipt-bulk-color">{{ uiText("颜色") }}<el-input v-model="spec.colorName" size="small" clearable :placeholder='uiText("如：黑色")' :maxlength="255" :aria-label="uiText('批量实收颜色')+(index?index+1:'')" :disabled="bulkDisabled"/></label>
      <label class="receipt-bulk-qty">{{ uiText("每条数量") }}<el-input-number v-model="spec.qty" :key="spec.id+':bulk-qty:'+bulkDisabled" size="small" :min="1" :max="Number.MAX_SAFE_INTEGER" :precision="0" :controls="false" :placeholder='uiText("选填")' :aria-label="uiText('批量实收数量')+(index?index+1:'')" :disabled="bulkDisabled"/></label>
      <el-button v-if="bulkSpecs.length>1" link type="danger" :aria-label="uiText('移除第{value0}条批量颜色',{value0:(index+1)})" :disabled="bulkDisabled" @click="removeBulkSpec(spec.id)">{{ uiText("删除") }}</el-button>
     </div>
     <el-button class="receipt-bulk-add" size="small" link type="primary" :disabled="bulkDisabled" @click="addBulkSpec">{{ uiText("增加颜色") }}</el-button>
    </div>
    <el-button class="receipt-bulk-apply" size="small" type="primary" :disabled="bulkDisabled||!selectedBulkLines.length||!hasBulkValues" @click="applyBulk">{{ uiText("应用到已勾选（") }}{{selectedBulkLines.length}}）</el-button>
   </div>
   <div class="receipt-table" role="table" :aria-label='uiText("本次实际收样")' v-loading="loading">
    <div class="receipt-head" role="row"><span role="columnheader">{{ uiText("图片") }}</span><span role="columnheader">{{ uiText("款式") }}</span><span role="columnheader">{{ uiText("实收型号") }}</span><span role="columnheader">{{ uiText("实收颜色") }}</span><span role="columnheader">{{ uiText("实收数量") }}</span><span role="columnheader">{{ uiText("操作") }}</span></div>
    <article v-for="line in lines" :key="line.clientRowId" class="receipt-row" role="row">
     <div class="receipt-image" role="cell"><PartnerPhoto v-for="file in photos(line).slice(0,1)" :key="file.id" :id="file.id"/><span v-if="!photos(line).length" class="receipt-placeholder">{{ uiText("暂无图片") }}</span></div>
     <div class="receipt-style" role="cell"><span>{{styleLabel(line)}}</span><el-checkbox v-if="showBulk" class="receipt-row-check" :model-value="checkedLines.has(line.clientRowId)" :aria-label="uiText('勾选{value0}收样规格',{value0:(styleName(line))})" :disabled="bulkDisabled" @change="checkBulkLine(line.clientRowId,Boolean($event))"><strong>{{styleName(line)}}</strong></el-checkbox><strong v-else>{{styleName(line)}}</strong></div>
     <div class="receipt-model" role="cell"><span class="receipt-label">{{ uiText("实收型号") }}</span><strong v-if="info(line)?.phones.length===1" class="receipt-phone-value">{{phoneLabel(line)}}</strong><el-select v-show="info(line)?.phones.length!==1" v-model="line.phoneModelId" filterable :placeholder='uiText("选择已选型号")' :aria-label="uiText('{value0}实收型号',{value0:(styleName(line))})" :disabled="busy||loading||unknown" @change="phoneChanged(line)"><el-option v-for="phone in info(line)?.phones||[]" :key="phone.phoneModelId" :value="phone.phoneModelId" :label="phone.displayName||phone.modelName"/></el-select></div>
     <div class="receipt-color" role="cell"><span class="receipt-label">{{ uiText("实收颜色") }}</span><el-autocomplete :model-value="colorValue(line)" :fetch-suggestions="colorSuggestions(line)" :debounce="0" clearable fit-input-width :highlight-first-item="false" :placeholder='uiText("直接填写，如：黑色")' :maxlength="255" :aria-label="uiText('{value0}实收颜色',{value0:(styleName(line))})" :disabled="busy||loading||unknown" @update:model-value="(value:string|number)=>colorInput(line,String(value??''))" @select="(color:Record<string,unknown>)=>selectColor(line,color)"><template #default="{item}"><span class="receipt-color-name">{{item.name}}</span><small v-if="item.description" class="receipt-color-detail">{{item.description}}</small></template></el-autocomplete></div>
     <div class="receipt-qty" role="cell"><span class="receipt-label">{{ uiText("实收数量") }}</span><el-input-number v-model="line.qty" :key="line.clientRowId+':actual:'+(busy||loading||unknown)" :min="1" :max="Number.MAX_SAFE_INTEGER" :precision="0" :controls="false" :placeholder='uiText("实收")' :aria-label="uiText('{value0}实收数量',{value0:(styleName(line))})" :disabled="busy||loading||unknown"/></div>
     <div class="receipt-actions" role="cell"><div v-show="phase==='edit'" class="receipt-action-buttons"><el-button link type="primary" :disabled="busy||loading||unknown" @click="addLine(line)">{{ uiText("增加规格") }}</el-button><el-button v-if="lines.length>1" link type="danger" :disabled="busy||loading||unknown" @click="removeLine(line)">{{ uiText("本次不收") }}</el-button></div></div>
     <div v-if="line.phoneModelId&&line.colorId" class="receipt-identity"><label>{{ uiText("样品码 · 相同物品沿用同一码") }}</label><el-select v-model="line.sampleIdentityId" clearable filterable remote :remote-method="(keyword:string)=>identitiesFor(line,keyword)" :loading="identityLoading.has(line.clientRowId)" :disabled="busy||loading||unknown" :placeholder='uiText("按真实来源自动复用／保存时新建主码")' :aria-label="styleName(line)+uiText('样品码')"><el-option v-for="identity in identityOptions.get(line.clientRowId)||[]" :key="identity.sampleIdentityId" :value="identity.sampleIdentityId" :label="identity.sampleCode+' · '+identity.name+' · '+identity.specName"/></el-select><p v-if="identityReview.get(line.clientRowId)">{{identityReview.get(line.clientRowId)}}</p><el-input v-if="needsIdentityReason(line)" v-model="line.identityReason" :disabled="busy||loading||unknown" :placeholder='uiText("核对同款、型号、颜色及版本的原因")' :aria-label='uiText("同物核对原因")'/></div>
     <div v-if="showIssues&&info(line)?.messages.length" class="receipt-row-errors" role="alert"><p v-for="message in info(line)?.messages" :key="message">{{message}}</p></div>
    </article>
   </div>
   <p class="receipt-summary">{{ uiText("本次 ") }}{{lines.length}}{{ uiText(" 条实收规格，合计 ") }}{{totalQty}}{{ uiText(" 件。保存后生成各自的真实样品批次号。") }}</p>
  </section>
  <section v-if="phase==='saved'&&!actorChanged" class="receipt-saved">
   <el-alert :title='uiText("实际收样已保存")' type="success" :closable="false"/>
   <p class="receipt-intro">{{ uiText("已保存实际数量；同物沿用样品码，收样批次留作内部追溯。") }}</p>
   <el-button v-if="printLabels.length" type="primary" size="small" @click="printOpen=true">{{ uiText("打印二维码标签") }}</el-button><div class="receipt-results"><article v-for="batch in result?.batches||[]" :key="batch.id"><strong>{{batch.sampleCode||batch.name}} · {{batch.name}}</strong><span>{{batch.phoneName}} · {{batch.colorName}} · {{batch.qty}}{{ uiText(" 件") }}</span><el-button v-if="batch.sampleIdentityId" link type="primary" @click="viewIdentity(batch.sampleIdentityId)">{{ uiText("查看样品台账") }}</el-button><el-button link @click="viewBatch(batch.id)">{{ uiText("内部收样原记录") }}</el-button></article></div>
  </section>
  <template #footer><div class="receipt-footer"><el-button v-if="actorChanged" @click="close">{{ uiText("关闭") }}</el-button><el-button v-if="!actorChanged&&phase==='saved'" type="primary" @click="close">{{ uiText("完成") }}</el-button><el-button v-if="!actorChanged&&phase==='edit'" :disabled="busy||unknown" @click="close">{{ uiText("取消") }}</el-button><el-button v-if="!actorChanged&&phase==='edit'" type="primary" :loading="busy" :disabled="loading||!lines.length" @click="save">{{unknown?uiText('核对本次保存结果'):uiText('保存实际收样')}}</el-button></div></template>
 </el-dialog>
 <SampleLabelPrint v-model="printOpen" :samples="printLabels"/>
</template>

<style scoped>
:global(.selection-receipt-dialog){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-top:20px;margin-bottom:20px;box-sizing:border-box}
:global(.selection-receipt-dialog>.el-dialog__header),:global(.selection-receipt-dialog>.el-dialog__footer){flex:none}
:global(.selection-receipt-dialog>.el-dialog__body){min-height:0;overflow-y:auto;overscroll-behavior:contain;container-type:inline-size}
.receipt-footer{display:flex;justify-content:flex-end;flex-wrap:wrap;gap:8px}.receipt-action-buttons{display:flex;flex-direction:column;align-items:flex-start;gap:4px}.receipt-intro{margin:0 0 12px;color:#586b80;font-size:13px;line-height:1.6}.receipt-common{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.2fr) minmax(0,1fr);gap:12px;margin-top:12px}.receipt-common :deep(.el-form-item){margin-bottom:12px;min-width:0}.receipt-common :deep(.el-date-editor){width:100%}
.receipt-table{border:1px solid #dce5ef;border-radius:5px;min-width:0}.receipt-head,.receipt-row{display:grid;grid-template-columns:66px minmax(0,1.2fr) minmax(0,1.45fr) minmax(0,1fr) 88px 76px;min-width:0}.receipt-head{background:#edf3f9;font-size:13px;color:#3a526d;font-weight:600}.receipt-head>span,.receipt-row>[role=cell]{padding:7px 8px;min-width:0;overflow-wrap:anywhere}.receipt-row{border-top:1px solid #dce5ef;align-items:center}.receipt-row:nth-child(odd){background:#fafcfe}.receipt-style{display:flex;flex-direction:column;gap:3px;font-size:14px}.receipt-style>span{color:#5a7898;font-size:12px}.receipt-phone-value{display:block;white-space:normal;overflow-wrap:anywhere;line-height:1.5}.receipt-model strong{color:#24466c;font-size:14px}.receipt-row :deep(.el-select),.receipt-row :deep(.el-autocomplete),.receipt-row :deep(.el-input-number){width:100%;min-width:0}.receipt-image{display:flex;justify-content:center}.receipt-placeholder{font-size:11px;color:#7c899b}.receipt-color,.receipt-actions{display:flex;flex-direction:column;align-items:flex-start;gap:4px}.receipt-row :deep(.el-button){margin:0;max-width:100%;height:auto;white-space:normal;line-height:1.5;font-size:12px;text-align:left}.receipt-row :deep(.el-button>span){white-space:normal;overflow-wrap:anywhere}.receipt-label{display:none}.receipt-errors,.receipt-row-errors{font-size:12px;color:#ad6713;line-height:1.6}.receipt-errors p,.receipt-row-errors p{margin:2px 0}.receipt-row-errors{grid-column:2/-1;padding:0 8px 7px}.receipt-summary{font-size:13px;color:#5c728b;margin:12px 0 0;line-height:1.6}.receipt-results{margin-top:10px}.receipt-results article{display:flex;flex-wrap:wrap;gap:5px 16px;align-items:center;border-bottom:1px solid #e3eaf3;padding:10px 0;font-size:14px}.receipt-results article>span{flex:1;min-width:160px;color:#536a84}.receipt-results :deep(.el-button){white-space:normal;height:auto;line-height:1.5}
.receipt-bulk{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:start;gap:8px;margin:0 0 8px;padding:8px;min-width:0;border:1px solid #c9d9ef;border-radius:4px;background:#f0f6ff;font-size:12px}.receipt-bulk-scope{display:flex;flex-wrap:wrap;align-items:center;align-self:center;gap:8px;color:#536a84}.receipt-bulk-line>label{display:flex;flex-direction:column;gap:4px;min-width:0;color:#354d67;font-weight:600}.receipt-bulk :deep(.el-input-number){width:100%;min-width:0}.receipt-bulk :deep(.el-input__wrapper){height:28px;box-sizing:border-box}.receipt-bulk :deep(.el-input__inner){font-size:12px;font-weight:400}.receipt-bulk .el-button{height:28px;margin:0;font-size:12px}.receipt-bulk-specs{display:flex;flex-direction:column;gap:6px;min-width:0}.receipt-bulk-line{display:grid;grid-template-columns:minmax(0,1fr) 120px 32px;align-items:end;gap:8px;min-width:0}.receipt-bulk-add{align-self:flex-start}.receipt-bulk-apply{align-self:start;margin-top:20px!important}.receipt-bulk :deep(.el-checkbox){height:28px}.receipt-bulk :deep(.el-checkbox__label){font-size:12px}.receipt-style :deep(.receipt-row-check){height:auto;max-width:100%;align-items:flex-start;white-space:normal;margin:0}.receipt-style :deep(.receipt-row-check .el-checkbox__input){margin-top:3px}.receipt-style :deep(.receipt-row-check .el-checkbox__label){min-width:0;padding-left:6px;white-space:normal;overflow-wrap:anywhere;color:inherit;font-size:14px;line-height:1.5}
@container(max-width:720px){.receipt-bulk{grid-template-columns:minmax(0,1fr)}.receipt-bulk-line{grid-template-columns:minmax(0,1fr) minmax(84px,.55fr) 32px}.receipt-bulk-apply{justify-self:start;margin-top:0!important}}
@container(max-width:720px){.receipt-common{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.receipt-common :deep(.el-form-item):last-child{grid-column:1/-1}.receipt-head{display:none}.receipt-row{grid-template-columns:58px minmax(0,1.1fr) minmax(0,1fr);align-items:start}.receipt-image{grid-row:1/3}.receipt-style{grid-column:2/-1}.receipt-model{grid-column:2/-1}.receipt-color{grid-column:2}.receipt-qty{grid-column:3}.receipt-actions{grid-column:2/-1;flex-direction:row;flex-wrap:wrap}.receipt-label{display:block;color:#6b7d91;font-size:12px;margin-bottom:4px}.receipt-row-errors{grid-column:1/-1}}
.receipt-identity{grid-column:1/-1;display:flex;flex-direction:column;gap:5px;padding:5px 0;color:#5b7390;font-size:12px}.receipt-identity .el-select{width:100%}.receipt-identity p{margin:0;color:#9a6a27}
.receipt-color-name{display:block;white-space:normal;overflow-wrap:anywhere;line-height:1.5}.receipt-color-detail{display:block;color:#6b7d91;white-space:normal;overflow-wrap:anywhere;line-height:1.4}
</style>

