<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {t as uiText} from '@/locales'
import {useUserStore} from '@/stores/user'
import {newRequestId} from '@/api/modules/partner'
import {querySampleIdentities,sampleIdentityAction,sampleIdentityBulkAction} from '@/api/modules/sampleManagement'
import type {SampleBatch,SampleIdentity} from '@/types/sampleManagement'
import {useSampleSubmission} from './useSampleSubmission'

const props=defineProps<{action:'correct_receipt'|'void_receipt';samples:SampleIdentity[]}>()
const emit=defineEmits<{close:[];saved:[ids:number[]];inspect:[batchId:number]}>(),user=useUserStore(),auth=user.token
const {busy,error,unknown,submit}=useSampleSubmission('receipt:'+props.action+':'+props.samples.map(row=>row.sampleIdentityId).sort((a,b)=>a-b).join(','))
interface ReceiptRow {sample:SampleIdentity;batch:SampleBatch;chosen:boolean}
const loading=ref(true),rows=ref<ReceiptRow[]>([]),chosenBatch=ref<number>(),correctedQty=ref<number>(),reason=ref('')
const deleting=computed(()=>props.action==='void_receipt')
const selectedRows=computed(()=>rows.value.filter(row=>deleting.value?row.chosen:row.batch.id===chosenBatch.value))
const total=computed(()=>selectedRows.value.reduce((sum,row)=>sum+effectiveQty(row.batch),0))
const blocked=computed(()=>selectedRows.value.find(row=>row.batch.canCorrectReceipt===false))
const canWrite=computed(()=>user.hasPermission('samples:write'))
const title=computed(()=>deleting.value?'删除收样登记':'修改收样数量')
let disposed=false
function effectiveQty(batch:SampleBatch){return batch.effectiveReceivedQty??batch.receivedQty??0}
function chooseBatch(id:number){chosenBatch.value=id;correctedQty.value=effectiveQty(rows.value.find(row=>row.batch.id===id)!.batch)}
function close(){if(!busy.value&&!unknown.value)emit('close')}
onMounted(async()=>{
 try{
  const {content:values}=await querySampleIdentities({ids:props.samples.map(row=>row.sampleIdentityId),details:true})
  if(disposed||auth!==user.token)return
  rows.value=values.flatMap(sample=>(sample.batches||[]).filter(batch=>batch.receiptStatus!=='voided'&&effectiveQty(batch)>0).map(batch=>({sample,batch,chosen:props.samples.length>1})))
  if(rows.value.length===1){rows.value[0]!.chosen=true;chooseBatch(rows.value[0]!.batch.id)}
  if(!rows.value.length&&!unknown.value)error.value='没有可修改的有效收样登记；已删除记录可在历史中查看。'
 }catch(e){if(!disposed&&auth===user.token)error.value=e instanceof Error?e.message:'读取收样登记失败，请关闭后重试'}
 finally{if(!disposed)loading.value=false}
})
async function save(){
 if(!canWrite.value||loading.value||busy.value)return
 if(!unknown.value){
  if(!selectedRows.value.length){error.value='请选择要'+(deleting.value?'删除':'修改')+'的收样登记';return}
  
  if(blocked.value){error.value=blocked.value.sample.sampleCode+'：'+(blocked.value.batch.receiptChangeBlockedReason||'已有后续流转，请先查看原记录处理');return}
  if(!reason.value.trim()){error.value='请选择或填写更正原因';return}
  if(!deleting.value&&(!Number.isSafeInteger(correctedQty.value)||Number(correctedQty.value)<=0)){error.value='正确数量须为正整数；整次登记错误请使用删除登记';return}
  if(!deleting.value&&correctedQty.value===effectiveQty(selectedRows.value[0]!.batch)){error.value='数量没有变化，无需保存';return}
 }
 const ids=selectedRows.value.length?[...new Set(selectedRows.value.map(row=>row.sample.sampleIdentityId))]:props.samples.map(row=>row.sampleIdentityId)
 const result=await submit<SampleIdentity|{content:SampleIdentity[]}>(()=>{
  const common={requestId:newRequestId(),reason:reason.value.trim()}
  if(deleting.value&&selectedRows.value.length>1){
   const items=[...new Set(selectedRows.value.map(row=>row.sample.sampleIdentityId))].map(id=>{
    const matches=selectedRows.value.filter(row=>row.sample.sampleIdentityId===id)
    return{sampleIdentityId:id,version:matches[0]!.sample.version,allocations:matches.map(row=>({batchId:row.batch.id,batchVersion:row.batch.version}))}
   })
   return{...common,items}
  }
  const row=selectedRows.value[0]!
  return{...common,sampleIdentityId:row.sample.sampleIdentityId,version:row.sample.version,batchId:row.batch.id,batchVersion:row.batch.version,...(!deleting.value?{qty:correctedQty.value}:{})}
 },payload=>payload.items?sampleIdentityBulkAction('void_receipt',payload):sampleIdentityAction(Number(payload.sampleIdentityId),props.action,payload))
 if(result&&!disposed&&auth===user.token)emit('saved',ids)
}
watch(()=>user.token,()=>emit('close'))
onBeforeUnmount(()=>{disposed=true})
</script>
<template>
 <el-dialog class="receipt-correction-dialog" :model-value="true" :title="uiText(title)" width="min(780px,96vw)" append-to-body :close-on-click-modal="false" :before-close="close">
  <div class="receipt-correction-content" v-loading="loading">
   <p class="receipt-explanation">{{uiText(deleting?'仅作废错误或重复登记，不删除产品、样品码、附件和操作历史。实际收到但不再使用的实物，不应当作错误登记删除。':'只更正本次收样登记；其他批次、原始记录和附件保留。')}}</p>
   <p v-if="rows.length>1">{{uiText(deleting?'勾选要删除的登记，未勾选的不变。':'请选择数量填错的那次收样。')}}</p>
   <div class="receipt-rows">
    <article v-for="row in rows" :key="row.batch.id" class="receipt-correction-row">
     <el-checkbox v-if="deleting" v-model="row.chosen" :disabled="busy||unknown" :aria-label="uiText('删除登记 ')+row.batch.code"/>
     <el-radio v-else :model-value="chosenBatch" :value="row.batch.id" :disabled="busy||unknown" :aria-label="uiText('修改登记 ')+row.batch.code" @change="chooseBatch(row.batch.id)"><span class="sr-only">{{uiText('选择本次登记')}}</span></el-radio>
     <div class="receipt-description"><strong>{{row.sample.sampleCode}} · {{row.sample.productName||row.sample.name}}</strong><span>{{row.batch.phoneName||row.sample.phoneName||row.sample.specName}} · {{row.batch.colorName||row.sample.colorName||uiText('颜色待核对')}}</span><small>{{row.batch.receivedDate||uiText('日期待核对')}} · {{row.batch.code}}<template v-if="row.batch.balances.positions?.length"> · {{row.batch.balances.positions.map(p=>p.name).join(' / ')}}</template></small><small v-if="row.batch.canCorrectReceipt===false" class="receipt-blocked">{{row.batch.receiptChangeBlockedReason||uiText('已有后续记录，请先查看原记录处理')}}</small></div>
     <strong class="receipt-quantity">{{effectiveQty(row.batch)}} {{uiText('件')}}<el-button v-if="row.batch.canCorrectReceipt===false" link size="small" :disabled="busy||unknown" @click="emit('inspect',row.batch.id)">{{uiText('查看原记录')}}</el-button></strong>
    </article>
   </div>
   <el-form label-position="top" :disabled="loading||busy||unknown">
    <el-form-item v-if="!deleting" :label="uiText('正确数量 *')"><el-input-number :key="'receipt-qty:'+(busy||unknown||!chosenBatch)" v-model="correctedQty" :min="1" :precision="0" :disabled="busy||unknown||!chosenBatch" :aria-label="uiText('正确收样数量')"/></el-form-item>
    <el-form-item :label="uiText('更正原因 *')"><el-select v-model="reason" filterable allow-create default-first-option :placeholder="uiText('选择原因，也可直接输入')" :aria-label="uiText('收样更正原因')"><el-option v-for="value in (deleting?['重复登记','这次实际未收到','型号或颜色选错']:['数量填错','重复计入数量'])" :key="value" :label="uiText(value)" :value="value"/></el-select></el-form-item>
   </el-form>
   <p v-if="deleting&&selectedRows.length" class="receipt-total">{{uiText('将删除')}} {{selectedRows.length}} {{uiText('条登记，共')}} {{total}} {{uiText('件；确认后同步更新有效到样和库存数量。')}}</p>
   <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  </div>
  <template #footer><el-button :disabled="busy||unknown" @click="close">{{uiText('返回')}}</el-button><el-button :type="deleting?'danger':'primary'" :loading="busy" :disabled="!canWrite||loading||(!rows.length&&!unknown)" @click="save">{{uiText(unknown?'核对本次保存结果':deleting?'确认删除登记':'确认修改')}}</el-button></template>
 </el-dialog>
</template>
<style scoped>
.receipt-correction-content{font-size:12px;line-height:1.6;min-width:0}.receipt-explanation{color:#62778f;margin:0 0 10px}.receipt-rows{margin-bottom:12px}.receipt-correction-row{display:flex;align-items:center;gap:8px;padding:8px 0;border-bottom:1px solid #e3ebf3;min-width:0}.receipt-description{min-width:0;flex:1;overflow-wrap:anywhere}.receipt-description strong,.receipt-description span,.receipt-description small{display:block}.receipt-description small{color:#77899e}.receipt-description .receipt-blocked{color:#c45656}.receipt-quantity{white-space:nowrap;color:#284764}.receipt-correction-content :deep(.el-form-item__label),.receipt-correction-content :deep(.el-input__inner){font-size:12px}.receipt-correction-content :deep(.el-select){width:100%}.receipt-total{color:#9b4e35}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}:global(.receipt-correction-dialog){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-block:20px;overflow:hidden}:global(.receipt-correction-dialog>.el-dialog__body){min-height:0;overflow:auto}:global(.receipt-correction-dialog>.el-dialog__header),:global(.receipt-correction-dialog>.el-dialog__footer){flex:none}
</style>
