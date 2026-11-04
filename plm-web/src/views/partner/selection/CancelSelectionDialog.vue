<script setup lang="ts">
import {computed,onMounted,onBeforeUnmount,ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {t as uiText} from '@/locales'
import {newRequestId} from '@/api/modules/partner'
import {previewSelectionCancellation,transitionSelectionRows} from '@/api/modules/partnerSelections'
import {partnerError} from '@/utils/partnerError'
import type {SelectionCancellationPreview,SelectionRow,SelectionSheet,SelectionTransition} from '@/types/partnerSelections'
const props=defineProps<{id:number;version:number;rows:SelectionRow[]}>()
const emit=defineEmits<{close:[];busy:[value:boolean];saved:[sheet:SelectionSheet,command:SelectionTransition]}>()
const router=useRouter(),preview=ref<SelectionCancellationPreview>(),loading=ref(false),saving=ref(false),unknown=ref(false),error=ref('')
const handling=ref<'keep'|'void'>('keep'),chosen=ref<number[]>([]),reason=ref('')
let pending:SelectionTransition|undefined
const locked=computed(()=>loading.value||saving.value||unknown.value)
const chosenReceipts=computed(()=>preview.value?.receipts.filter(row=>chosen.value.includes(row.id)&&row.canVoid)||[])
const chosenQty=computed(()=>chosenReceipts.value.reduce((sum,row)=>sum+row.effectiveReceivedQty,0))
watch(locked,value=>emit('busy',value),{flush:'sync'})
onBeforeUnmount(()=>emit('busy',false))
async function load(){loading.value=true;error.value='';try{preview.value=await previewSelectionCancellation(props.id,{version:props.version,rows:props.rows.map(row=>({rowId:row.rowId,rowVersion:row.rowVersion||0}))})}catch(cause){error.value=partnerError(cause)}finally{loading.value=false}}
onMounted(load)
function choose(id:number,value:boolean){if(locked.value)return;chosen.value=value?[...chosen.value,id]:chosen.value.filter(value=>value!==id)}
function resultUnknown(cause:unknown){const failure=cause as {code?:string;isAxiosError?:boolean;response?:{status?:number}};return ['ECONNABORTED','ETIMEDOUT','ERR_NETWORK'].includes(failure.code||'')||Number(failure.response?.status)>=500||!!failure.isAxiosError&&!failure.response}
async function submit(){
 if(!preview.value||saving.value||loading.value)return
 error.value=''
 if(!pending){
  if(handling.value==='void'&&!chosenReceipts.value.length){error.value='请勾选确实需要删除的错误收样登记';return}
  if(handling.value==='void'&&!reason.value.trim()){error.value='请填写删除误登记的原因';return}
  pending={version:preview.value.version,requestId:newRequestId(),action:'cancel',rows:props.rows.map(row=>({rowId:row.rowId,rowVersion:row.rowVersion||0})),receiptHandling:handling.value,...(handling.value==='void'?{receipts:chosenReceipts.value.map(row=>({id:row.id,version:row.version})),reason:reason.value.trim()}:{})}
 }
 saving.value=true
 try{const sheet=await transitionSelectionRows(props.id,pending);unknown.value=false;emit('saved',sheet,pending)}catch(cause){unknown.value=resultUnknown(cause);error.value=unknown.value?'保存结果待核对，请点击“核对取消结果”；会沿用原提交，不重复扣减样品。':partnerError(cause);if(!unknown.value)pending=undefined}finally{saving.value=false}
}
</script>

<template>
 <el-dialog :model-value="true" class="cancel-selection-dialog" :title="uiText('取消选品')" width="min(820px,96vw)" top="20px" append-to-body :close-on-click-modal="false" :close-on-press-escape="!locked" :show-close="!locked" :before-close="()=>!locked&&emit('close')">
  <div class="cancel-selection-content" v-loading="loading">
   <p>{{uiText('将取消 {count} 款选品',{count:rows.length})}}<span class="cancel-names">{{rows.map(row=>row.name).join('、')}}</span></p>
   <template v-if="preview">
    <el-radio-group v-model="handling" :disabled="locked" :aria-label="uiText('取消时如何处理样品')"><el-radio value="keep">{{uiText('仅取消选品，保留样品')}}</el-radio><el-radio v-if="preview.receipts.length&&preview.canVoidReceipts" value="void">{{uiText('同时删除误登记的收样')}}</el-radio></el-radio-group>
    <p class="cancel-note">{{uiText('取消不会删除供应商产品、样品码、附件和历史。再次选用时可重新加入。')}}</p>
    <p v-if="!preview.receipts.length" class="cancel-note">{{uiText('这些选品没有有效收样登记。')}}</p>
    <section v-else class="cancel-receipts">
     <strong>{{uiText('关联的有效收样登记')}} · {{preview.receipts.length}}</strong>
     <p v-if="handling==='void'" class="cancel-note">{{uiText('只勾选错误或重复登记；真实收到但不再使用的样品请保留。未勾选的登记不受影响。')}}</p>
     <article v-for="receipt in preview.receipts" :key="receipt.id" class="cancel-receipt">
      <el-checkbox v-if="handling==='void'" :model-value="chosen.includes(receipt.id)" :disabled="locked||!receipt.canVoid" :aria-label="uiText('删除登记 {code}',{code:receipt.displayCode||receipt.code})" @update:model-value="choose(receipt.id,Boolean($event))"/>
      <div><strong>{{receipt.displayCode||receipt.code}} · {{receipt.name}}</strong><span>{{[receipt.date,receipt.modelName,receipt.colorName].filter(Boolean).join(' · ')}} · {{receipt.effectiveReceivedQty}} {{uiText('件')}}</span><small v-if="!receipt.canVoid">{{uiText(receipt.blockedReason)}}</small></div>
      <a :href="router.resolve({path:'/samples',query:{id:receipt.id}}).href" target="_blank" rel="noopener">{{uiText('查看原记录')}}</a>
     </article>
    </section>
    <template v-if="handling==='void'"><label for="cancel-receipt-reason">{{uiText('删除误登记的原因')}} *</label><el-input id="cancel-receipt-reason" v-model="reason" :disabled="locked" type="textarea" :autosize="{minRows:2,maxRows:4}" :aria-label="uiText('删除误登记的原因')"/><p>{{uiText('将删除 {count} 条误登记，共 {qty} 件；原始记录和历史保留。',{count:chosenReceipts.length,qty:chosenQty})}}</p></template>
   </template>
   <p v-if="error" class="cancel-error" role="alert">{{uiText(error)}}</p>
  </div>
  <template #footer><el-button v-if="!preview" :disabled="loading" @click="load">{{uiText('重新读取')}}</el-button><el-button :disabled="locked" @click="emit('close')">{{uiText('返回')}}</el-button><el-button type="danger" :loading="saving" :disabled="!preview||loading||saving" @click="submit">{{unknown?uiText('核对取消结果'):handling==='void'?uiText('取消选品并删除勾选登记'):uiText('仅取消选品，保留样品')}}</el-button></template>
 </el-dialog>
</template>

<style scoped>
:global(.cancel-selection-dialog){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-bottom:20px;font-size:12px}
:global(.cancel-selection-dialog>.el-dialog__body){min-height:0;overflow:auto}:global(.cancel-selection-dialog .el-dialog__title){font-size:16px}:global(.cancel-selection-dialog>.el-dialog__footer){display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap}:global(.cancel-selection-dialog .el-button){font-size:12px;height:auto;min-height:28px;white-space:normal;margin:0}
.cancel-selection-content{font-size:12px;line-height:1.6;min-width:0}.cancel-selection-content p{margin:0 0 8px}.cancel-names,.cancel-note{display:block;color:var(--el-text-color-secondary);overflow-wrap:anywhere}.cancel-selection-content :deep(.el-radio-group){display:flex;gap:4px 16px;align-items:flex-start}.cancel-selection-content :deep(.el-radio){margin:0;height:auto;min-height:28px;white-space:normal}.cancel-selection-content :deep(.el-radio__label){font-size:12px;white-space:normal;line-height:1.6}.cancel-receipts{margin:10px 0}.cancel-receipt{display:flex;gap:8px;align-items:flex-start;border-bottom:1px solid var(--el-border-color-lighter);padding:8px 0}.cancel-receipt>div{flex:1;min-width:0;overflow-wrap:anywhere}.cancel-receipt span,.cancel-receipt small{display:block}.cancel-receipt small,.cancel-error{color:var(--el-color-danger)}.cancel-receipt>a{flex:none;color:var(--el-color-primary);text-decoration:none}.cancel-receipt :deep(.el-checkbox){height:22px;margin:0}
</style>
