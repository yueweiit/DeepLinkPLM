<script setup lang="ts">
import {computed,onBeforeUnmount,ref} from 'vue'
import {ElMessage} from 'element-plus'
import {Refresh,Upload} from '@element-plus/icons-vue'
import {crmListingPushOptions,pushCrmListings,retryCrmListing} from '@/api/modules/partnerOperations'
import {newRequestId} from '@/api/modules/partner'
import type {CrmListingPushOptions,ListedCatalogRow,CrmListingSyncStatus} from '@/types/partnerLifecycle'
import {partnerError} from '@/utils/partnerError'
import PartnerPhoto from '../PartnerPhoto.vue'

const props=defineProps<{rows:ListedCatalogRow[];readonly?:boolean}>()
const emit=defineEmits<{close:[];saved:[]}>()
const options=ref<CrmListingPushOptions[]>([]),selected=ref<Record<number,number[]>>({}),loading=ref(false),saving=ref(false),error=ref('')
const syncLabels:Record<CrmListingSyncStatus,string>={not_submitted:'未提交',pending:'等待推送',processing:'推送中',partial:'部分失败',synced:'已同步 CRM',failed:'推送失败'}
const listingLabels:Record<string,string>={not_listed:'未上架',listing:'上架中',listed:'已上架',listing_failed:'上架失败',off_shelf:'已下架'}
let generation=0,requestId='',requestBody='',retryRequestId='',retryTaskId=0
async function load(){
 const serial=++generation;loading.value=true;error.value='';options.value=[]
 try{const data=await Promise.all(props.rows.map(row=>crmListingPushOptions(row.listingId!)));if(serial!==generation)return;options.value=data;selected.value=Object.fromEntries(data.map(o=>[o.listingId,[]]));requestId='';requestBody=''}
 catch(e){if(serial===generation)error.value=partnerError(e)}finally{if(serial===generation)loading.value=false}
}
onBeforeUnmount(()=>{generation++})
void load()
const canSubmit=computed(()=>!props.readonly&&!loading.value&&!saving.value&&options.value.length>0&&options.value.length===props.rows.length&&options.value.every(o=>o.canSubmit&&!o.reasons.length&&(selected.value[o.listingId]||[]).length>0))
async function submit(){
 if(!canSubmit.value)return
 const items=options.value.map(o=>({listingId:o.listingId,snapshotVersion:o.snapshotVersion,internalSkuIds:[...(selected.value[o.listingId]||[])].sort((a,b)=>a-b)}))
 const body=JSON.stringify(items);if(body!==requestBody){requestId=newRequestId();requestBody=body}
 saving.value=true;error.value=''
 try{await pushCrmListings(requestId,items);ElMessage.success('已提交推送任务');emit('saved');emit('close')}
 catch(e){error.value=partnerError(e)}finally{saving.value=false}
}
async function retry(option:CrmListingPushOptions){
 if(!option.canSubmit||!option.sync.taskId||saving.value)return
 if(retryTaskId!==option.sync.taskId){retryTaskId=option.sync.taskId;retryRequestId=newRequestId()}
 saving.value=true;error.value=''
 try{await retryCrmListing(option.listingId,option.sync.taskId,retryRequestId);ElMessage.success('已提交重试');emit('saved');await load()}
 catch(e){error.value=partnerError(e)}finally{saving.value=false}
}
</script>
<template>
 <el-dialog :model-value="true" :title="readonly?'CRM 同步结果':'推送 CRM'" width="min(900px, calc(100vw - 24px))" :close-on-click-modal="false" @close="!saving&&emit('close')">
  <div v-loading="loading" class="crm-push-content">
   <el-alert v-if="error" :title="error" type="error" :closable="false"/>
   <section v-for="option in options" :key="option.listingId" class="crm-push-product">
    <div class="crm-product-head"><PartnerPhoto v-if="option.product.photos[0]" :id="option.product.photos[0].id"/><div><strong>{{option.product.name}}</strong><div>{{option.product.catalogStyleCode||option.product.productCode}}</div></div><el-tag>{{syncLabels[option.sync.status]}}</el-tag></div>
    <el-alert v-if="!option.canSubmit&&!readonly" :title="option.reason" type="warning" :closable="false"/>
    <el-alert v-for="reason in option.reasons" :key="reason" :title="reason" type="warning" :closable="false"/>
    <p v-if="option.sync.errorMessage" class="crm-error">{{option.sync.errorMessage}}</p>
    <el-checkbox-group v-if="!readonly" v-model="selected[option.listingId]" :disabled="saving">
     <div v-for="sku in option.skus" :key="sku.specId" class="crm-sku-row">
      <el-checkbox :value="sku.internalSkuId" :disabled="!sku.eligible||!option.canSubmit||!!option.reasons.length"><span>{{sku.skuCode||sku.specName}}</span></el-checkbox>
      <span>{{sku.skuName}}</span><span>{{sku.phoneModelName}} {{sku.phoneModelCode}}</span><span>{{sku.color}} {{sku.colorCode}}</span>
      <small v-if="!sku.eligible" class="crm-error">{{sku.reason}}</small>
     </div>
    </el-checkbox-group>
    <div v-if="readonly" class="crm-sync-rows"><div v-for="sku in option.sync.skus" :key="sku.internalSkuId" class="crm-sync-row"><span>{{option.skus.find(s=>s.internalSkuId===sku.internalSkuId)?.skuCode||sku.internalSkuId}}</span><span>{{sku.status==='synced'?'已同步 CRM':sku.status==='failed'?'推送失败':'等待推送'}}</span><span>{{listingLabels[sku.listingStatus]||'未上架'}}</span><small v-if="sku.errorMessage" class="crm-error">{{sku.errorMessage}}</small></div></div>
    <el-button v-if="readonly&&['failed','partial'].includes(option.sync.status)" :icon="Refresh" :disabled="!option.canSubmit||saving" @click="retry(option)">重试失败项</el-button>
   </section>
  </div>
  <template #footer><div class="crm-push-actions"><el-button :icon="Refresh" :disabled="loading||saving" @click="load">刷新</el-button><el-button :disabled="saving" @click="emit('close')">关闭</el-button><el-button v-if="!readonly" type="primary" :icon="Upload" :loading="saving" :disabled="!canSubmit" @click="submit">确认推送</el-button></div></template>
 </el-dialog>
</template>
<style scoped>
.crm-push-actions{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px}.crm-push-actions .el-button{margin-left:0}
.crm-sku-row{font-size:14px;line-height:20px}
.crm-push-content{min-height:100px;max-height:65vh;overflow:auto}.crm-push-product{border-bottom:1px solid var(--el-border-color-lighter);padding:12px 0}.crm-product-head{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:10px}.crm-product-head :deep(.partner-photo){width:48px;height:48px}.crm-product-head>div{min-width:0;overflow-wrap:anywhere;flex:1}.crm-push-product .el-alert{margin:8px 0}.crm-sku-row,.crm-sync-row{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(0,1fr) minmax(0,1fr) minmax(0,.7fr);gap:6px;align-items:center;border-top:1px solid var(--el-border-color-lighter);padding:8px 0;overflow-wrap:anywhere}.crm-sku-row :deep(.el-checkbox){max-width:100%;height:auto;white-space:normal}.crm-sku-row :deep(.el-checkbox__label){min-width:0;white-space:normal;overflow-wrap:anywhere}.crm-error{color:var(--el-color-danger);grid-column:1/-1;margin:4px 0}.crm-sync-row{font-size:13px;grid-template-columns:repeat(3,minmax(0,1fr))}@media(max-width:600px){.crm-sku-row{grid-template-columns:repeat(2,minmax(0,1fr))}.crm-product-head strong{font-size:15px}}
</style>
