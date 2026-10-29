<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {useUserStore} from '@/stores/user'
import * as api from '@/api/modules/moldSales'
const model=defineModel<boolean>({default:false}),emit=defineEmits<{changed:[]}>(),user=useUserStore()
const data=ref<api.HistoryReviewPage>(),sources=ref<api.HistoryReviewSource[]>([]),selected=ref(''),preview=ref<api.HistoryReviewSource>(),details=ref<api.HistoryReviewDetail>()
const page=ref(1),keyword=ref(''),classification=ref('all'),loading=ref(false),sourceLoading=ref(false),saving=ref(false),error=ref(''),unknown=ref(false),orderPage=ref(1),currentGroup=ref<api.HistoryReviewGroup>()
const reason=ref('按2026-10-28已确认的三项推荐处理，保留原单原码；旧码只做型号对照，未核实不合并模具成本')
let epoch=0,sourceEpoch=0,detailEpoch=0,timer:ReturnType<typeof setTimeout>|undefined,poll:ReturnType<typeof setTimeout>|undefined,disposed=false
const chosen=computed(()=>sources.value.find(s=>s.sha256===selected.value)),summary=computed(()=>data.value?.summary||preview.value?.summary||chosen.value?.summary)
const labels:Record<api.HistoryClassification,string>={coded_history:'按原编码整理',purchased:'外购销量',pending:'历史待补'}
const qty=(n:number|string|undefined|null)=>n==null?'—':new Intl.NumberFormat('zh-CN').format(Number(n))
const canApply=computed(()=>!!preview.value&&!preview.value.applied&&!data.value?.applied&&!loading.value&&!sourceLoading.value&&!saving.value&&!unknown.value&&!!reason.value.trim())
function failure(e:unknown){return e instanceof Error?e.message:'读取失败，请重试'}
async function load(){
 const id=++epoch,auth=user.token;loading.value=true;error.value=''
 try{const value=await api.getHistoryReview({page:page.value,size:30,keyword:keyword.value.trim(),classification:classification.value});if(disposed||id!==epoch||auth!==user.token||!model.value)return;data.value=value}
 catch(e){if(id===epoch&&auth===user.token)error.value=failure(e)}finally{if(id===epoch)loading.value=false}
}
async function loadSources(){
 const id=++sourceEpoch,auth=user.token;sourceLoading.value=true
 try{const value=await api.getHistoryReviewSources();if(disposed||id!==sourceEpoch||auth!==user.token||!model.value)return;sources.value=value;selected.value=value.some(s=>s.sha256===selected.value)?selected.value:value[0]?.sha256||''}
 catch(e){if(id===sourceEpoch&&auth===user.token)error.value=failure(e)}finally{if(id===sourceEpoch)sourceLoading.value=false}
}
async function refresh(){await load();if(!data.value?.applied)await loadSources()}
async function makePreview(){
 const source=chosen.value;if(!source||saving.value)return;const id=++sourceEpoch,auth=user.token;sourceLoading.value=true;preview.value=undefined;error.value=''
 try{const value=await api.previewHistoryReview({file:source.file,sha256:source.sha256});if(id===sourceEpoch&&auth===user.token&&model.value){preview.value=value;unknown.value=false}}
 catch(e){if(id===sourceEpoch&&auth===user.token)error.value=failure(e)}finally{if(id===sourceEpoch)sourceLoading.value=false}
}
async function checkResult(hash:string){
 const auth=user.token
 try{
  const job=await api.getMoldSalesJob();if(disposed||auth!==user.token||!model.value)return
  if(job.kind==='historyReview'&&job.sourceHash===hash&&job.status==='running'){poll=setTimeout(()=>void checkResult(hash),1500);return}
  await load();if(auth!==user.token||disposed)return
  if(data.value?.applied&&data.value.policyHash===hash){saving.value=false;unknown.value=false;preview.value=undefined;emit('changed')}
  else{saving.value=false;unknown.value=true;error.value=job.kind==='historyReview'&&job.sourceHash===hash&&job.status==='failed'?job.message||'本次未处理，原资料保留':'结果尚未核实，请刷新处理状态；不要重复追加'}
 }catch(e){if(auth===user.token){saving.value=false;unknown.value=true;error.value=failure(e)+'。请刷新处理状态核对，不要重复追加'}}
}
async function apply(){
 if(!canApply.value||!preview.value)return;const value=preview.value,auth=user.token;saving.value=true;error.value=''
 try{const job=await api.applyHistoryReview({file:value.file,sha256:value.sha256,reason:reason.value.trim()});if(disposed||auth!==user.token)return;if(job.status==='running')poll=setTimeout(()=>void checkResult(value.sha256),1000);else await checkResult(value.sha256)}
 catch(e){if(auth===user.token){saving.value=false;unknown.value=true;error.value=failure(e)+'。请先刷新处理状态确认结果'}}
}
async function reconcile(){const auth=user.token;try{await refresh();if(auth!==user.token||!model.value)return;if(data.value?.applied){unknown.value=false;emit('changed');return}const job=await api.getMoldSalesJob();if(auth!==user.token||!model.value)return;if(job.status==='running'&&job.kind==='historyReview'&&job.sourceHash){saving.value=true;void checkResult(job.sourceHash)}else if(job.status!=='running')unknown.value=false}catch(e){if(auth===user.token)error.value=failure(e)}}
async function orders(group=currentGroup.value){
 if(!group)return;currentGroup.value=group;const id=++detailEpoch,auth=user.token;loading.value=true;error.value=''
 try{const value=await api.getHistoryReviewOrders({styleCode:group.styleCode,phoneCode:group.phoneCode,page:orderPage.value,size:20});if(id===detailEpoch&&auth===user.token&&model.value)details.value=value}
 catch(e){if(id===detailEpoch&&auth===user.token)error.value=failure(e)}finally{if(id===detailEpoch)loading.value=false}
}
function showOrders(group:api.HistoryReviewGroup){orderPage.value=1;void orders(group)}
function back(){++detailEpoch;details.value=undefined;currentGroup.value=undefined;loading.value=false}
function resetPage(){page.value=1;void load()}
watch(classification,()=>{if(page.value!==1)page.value=1;else if(model.value&&!details.value)void load()})
watch(page,()=>{if(model.value&&!details.value)void load()})
watch(keyword,()=>{clearTimeout(timer);timer=setTimeout(resetPage,300)})
watch(selected,()=>{preview.value=undefined})
watch(model,open=>{++epoch;++sourceEpoch;++detailEpoch;clearTimeout(poll);clearTimeout(timer);if(open){page.value=1;classification.value='all';keyword.value='';details.value=undefined;void refresh()}else{saving.value=false;preview.value=undefined;loading.value=false;sourceLoading.value=false}},{immediate:true})
watch(()=>user.token,()=>{++epoch;++sourceEpoch;++detailEpoch;clearTimeout(poll);clearTimeout(timer);data.value=undefined;sources.value=[];preview.value=undefined;details.value=undefined;model.value=false;saving.value=false;unknown.value=false;error.value=''})
onBeforeUnmount(()=>{disposed=true;++epoch;++sourceEpoch;++detailEpoch;clearTimeout(poll);clearTimeout(timer)})
</script>
<template>
 <el-drawer v-model="model" class="history-review" title="历史订单处理" size="min(1100px,96vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
  <div class="review-toolbar"><el-button v-if="details" size="small" @click="back">返回分类列表</el-button><span class="review-scope">原始订单和编码保留；型号对照不合并实物模具或成本。</span><el-button size="small" :disabled="saving" @click="reconcile">刷新处理状态</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <p v-if="saving" class="review-note" role="status">正在保存已确认方案，请等待结果；原单、累计产量和模具成本保持原依据。</p>
  <div v-if="summary" class="review-summary"><span v-for="kind in (['coded_history','purchased','pending'] as const)" :key="kind">{{labels[kind]}} <b>{{qty(summary.categories[kind]?.quantity)}}</b> 件</span></div>
  <section v-if="!data?.applied&&!details" class="review-prepare">
   <template v-if="sources.length"><el-select v-if="sources.length>1" v-model="selected" size="small" aria-label="已确认历史处理方案"><el-option v-for="s in sources" :key="s.sha256" :value="s.sha256" :label="`${qty(s.summary.groups)}组 · ${qty(s.summary.quantity)}件`"/></el-select><span v-else>已准备确认范围：{{qty(chosen?.summary.groups)}} 组、{{qty(chosen?.summary.rows)}} 条原单</span><el-button size="small" :loading="sourceLoading" :disabled="saving" @click="makePreview">核对处理范围</el-button><el-button type="primary" size="small" :disabled="!canApply" :loading="saving" @click="apply">应用已确认方案</el-button></template>
   <span v-else-if="!sourceLoading">尚未准备历史处理资料，请联系管理员。</span>
   <template v-if="preview"><p class="review-note">已核对 {{qty(preview.summary.quantity)}} 件。只新增历史分类及型号对照，不生成实物、套数或成本事实。</p><el-input v-model="reason" aria-label="处理依据" maxlength="1000" :disabled="saving"/></template>
  </section>
  <p v-if="data?.applied&&!details" class="review-note">已由 {{data.appliedBy}} 保存本批 {{qty(data.summary?.groups)}} 组、{{qty(data.summary?.rows)}} 条明细。实际成本和套数未核定的继续待补。</p>
  <template v-if="!details">
   <div class="review-filters"><el-input v-model="keyword" size="small" clearable placeholder="搜索款式、原手机编码、型号或对照编码" aria-label="历史资料搜索"/><el-select v-model="classification" size="small" aria-label="历史分类"><el-option value="all" label="全部历史资料"/><el-option v-for="kind in (['coded_history','purchased','pending'] as const)" :key="kind" :value="kind" :label="labels[kind]"/></el-select></div>
   <div class="review-list" v-loading="loading" role="table" aria-label="历史订单分类列表">
    <div class="review-row review-head" role="row"><span>款式</span><span>手机型号／原编码</span><span>订单件数</span><span>处理结果</span><span>原单</span></div>
    <article v-for="r in data?.content" :key="r.styleCode+r.phoneCode" class="review-row" role="row"><span><b>{{r.evidence.styleName}}</b><small>{{r.styleCode}}</small></span><span><b>{{r.evidence.phoneName}}</b><small>原编码 {{r.phoneCode}}</small></span><strong class="review-quantity">{{qty(r.quantity)}}</strong><span>{{labels[r.classification]}}<small v-if="r.classification==='purchased'">不分摊自有模具成本</small><small v-else>套数、实际成本待补</small><small v-if="r.evidence.sourceMoldCodes?.length">{{r.evidence.sourceMoldCodes.join(' / ')}}</small><small v-if="r.evidence.codeConflict">原码有差异，保留待核</small></span><el-button size="small" link type="primary" @click="showOrders(r)">查看原单</el-button></article>
    <el-empty v-if="!loading&&!data?.content.length" :description="data?.applied?'没有符合条件的历史资料':'核对并应用已确认方案后，可在此查看原单及型号对照'"/>
   </div>
   <el-pagination v-if="data?.applied" size="small" layout="total,prev,pager,next" :total="data.totalElements" :page-size="30" v-model:current-page="page"/>
  </template>
  <template v-else>
   <p class="review-note"><b>{{details.group.evidence.styleName}} · {{details.group.evidence.phoneName}}</b> · 原编码 {{currentGroup?.phoneCode}} · {{labels[details.group.classification]}}</p>
   <p v-if="details.group.evidence.recommendation" class="review-note">{{details.group.evidence.recommendation}}</p>
   <details class="review-evidence"><summary>原单型号与核对依据</summary><p>{{details.group.evidence.originalNamesWithQuantity}}</p><p v-if="details.group.evidence.originalMoldCodes">原模具编码：{{details.group.evidence.originalMoldCodes}}</p><p>{{details.group.evidence.missingEvidence}}</p></details>
   <div class="review-list order-list" role="table" aria-label="历史原单与型号对照" v-loading="loading">
    <div class="order-row review-head" role="row"><span>日期／订单</span><span>原型号／原手机编码</span><span>型号对照（仅显示）</span><span>原商品编码／数量</span></div>
    <article v-for="r in details.orders" :key="r.sourceId" class="order-row" role="row"><span>{{r.orderDate}}<small>{{r.orderNo}} · {{r.company}}</small><details><summary>原件位置</summary><small>{{r.sourceFile}} · {{r.sourceSheet}} · 第 {{r.sourceRow}} 行</small></details></span><span>{{r.originalName}}<small>{{r.phoneCode}}</small></span><span>{{r.displayName||'原名保留'}}<small v-if="r.displayPhoneCode">参考资料 {{r.displayPhoneCode}}（不确认共模）</small><details v-if="r.mappingReason"><summary>对照依据</summary><small>{{r.mappingReason}}</small></details></span><span>{{r.itemCode}}<strong class="review-quantity">{{qty(r.quantity)}} {{r.unit}}</strong></span></article>
   </div>
   <el-pagination size="small" layout="total,prev,pager,next" :total="details.totalElements" :page-size="20" v-model:current-page="orderPage" @current-change="orders()"/>
  </template>
 </el-drawer>
</template>
<style scoped>
:global(.history-review .el-drawer__header){padding:12px;margin-bottom:8px}:global(.history-review .el-drawer__body){display:flex;flex-direction:column;gap:8px;padding:12px;min-height:0;overflow:hidden}.review-toolbar,.review-summary,.review-prepare,.review-filters{display:flex;gap:8px;align-items:center;flex-wrap:wrap;flex:none}.review-scope,.review-note{font-size:12px;color:#63758d;line-height:18px;margin:0;overflow-wrap:anywhere}.review-scope{flex:1}.review-summary{font-size:13px;padding:6px 0;border-bottom:1px solid #e4eaf2}.review-summary span{margin-right:12px}.review-prepare{padding:8px;border:1px solid #e2e9f3;border-radius:4px;font-size:13px}.review-prepare .review-note,.review-prepare :deep(.el-input){flex-basis:100%}.review-filters :deep(.el-input){flex:1;min-width:180px}.review-filters :deep(.el-select){width:180px}.review-list{flex:1;min-height:0;overflow:auto;border:1px solid #e1e8f1}.review-row{display:grid;grid-template-columns:minmax(100px,.85fr) minmax(190px,1.65fr) 105px minmax(190px,1.5fr) 75px;gap:10px;padding:8px;align-items:center;font-size:13px;border-bottom:1px solid #edf1f6}.review-row>*,.order-row>*{min-width:0;overflow-wrap:anywhere}.review-row small,.order-row small{display:block;font-size:12px;color:#6c7e95;line-height:18px}.review-head{background:#f1f5fa;font-weight:600;position:sticky;top:0;z-index:1}.review-quantity{display:block;text-align:right;font-variant-numeric:tabular-nums;color:#285b9d}.order-row{display:grid;grid-template-columns:minmax(150px,1.2fr) minmax(160px,1.25fr) minmax(200px,1.7fr) minmax(160px,1.25fr);gap:10px;padding:8px;border-bottom:1px solid #e8eef5;font-size:13px}.order-row summary{font-size:12px;color:#42698e;cursor:pointer}.review-evidence{flex:none;font-size:12px;color:#63758d}.review-evidence summary{cursor:pointer}.review-evidence p{margin:4px 0;overflow-wrap:anywhere}.review-list{container-type:inline-size}@media(max-width:850px){.review-row,.order-row{grid-template-columns:minmax(0,1fr) minmax(0,1.4fr)}.review-head{display:none}.review-row>strong{grid-column:1;text-align:left}.review-row>:nth-child(4){grid-column:2;grid-row:2 / span 2}.review-row>:last-child{justify-self:start}.order-row{gap:8px}.order-row>:last-child{grid-column:2}.review-row small,.order-row small{white-space:normal}}
</style>
