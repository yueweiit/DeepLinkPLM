<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {applyPhoneBrandReview,getPhoneBrandReview} from '@/api/modules/productionMetadata'
import {useUserStore} from '@/stores/user'
import {useCollaborationWorkspace} from '@/stores/collaborationWorkspace'
import type {PhoneBrandReviewPreview} from '@/types/production'
const props=defineProps<{modelValue:boolean;editable:boolean}>()
const emit=defineEmits<{'update:modelValue':[boolean];saved:[];locate:[string]}>()
const user=useUserStore(),workspace=useCollaborationWorkspace(),preview=ref<PhoneBrandReviewPreview>(),loading=ref(false),busy=ref(false),error=ref(''),success=ref(''),reason=ref(''),tab=ref('ready'),keyword=ref(''),page=ref(1)
const visible=computed({get:()=>props.modelValue,set:(v:boolean)=>emit('update:modelValue',v)})
const candidates=computed(()=>preview.value?.items.filter(i=>i.state==='ready')||[])
const distribution=computed(()=>{const counts=new Map<string,number>();for(const item of candidates.value)for(const name of item.brandNames)counts.set(name,(counts.get(name)||0)+1);return [...counts].sort((a,b)=>b[1]-a[1]).map(([name,count])=>`${name} ${count}`).join(' · ')})
const filtered=computed(()=>{const q=keyword.value.trim().toLocaleLowerCase();return (preview.value?.items||[]).filter(i=>i.state===tab.value&&(!q||[i.codeValue,i.codeName,...i.brandNames,i.reason,...i.evidence].join(' ').toLocaleLowerCase().includes(q)))})
const records=computed(()=>filtered.value.slice((page.value-1)*50,page.value*50))
const canApply=computed(()=>props.editable&&!loading.value&&!busy.value&&!error.value&&!!candidates.value.length&&!!reason.value.trim())
let generation=0
async function load(){
  if(!props.modelValue||!props.editable||!user.token)return
  const epoch=++generation,token=user.token;loading.value=true;error.value=''
  try{const result=await getPhoneBrandReview();if(epoch===generation&&token===user.token&&props.modelValue){preview.value=result;page.value=1;if(!result.candidateCount&&result.unresolvedCount)tab.value='unresolved'}}
  catch(e){if(epoch===generation&&token===user.token)error.value=e instanceof Error?e.message:'品牌预览失败，请重新核对'}
  finally{if(epoch===generation)loading.value=false}
}
async function apply(){
  if(!canApply.value)return
  const epoch=generation,token=user.token;busy.value=true;error.value=''
  const items=candidates.value.map(({id,codeValue,codeName,originalBrandIds,brandIds})=>({id,codeValue,codeName,originalBrandIds,brandIds}))
  try{const result=await applyPhoneBrandReview(items,reason.value.trim());if(epoch!==generation||token!==user.token||!props.modelValue||!props.editable)return;workspace.changed();emit('saved');success.value=`已确认 ${result.appliedCount} 个编码组的品牌`;busy.value=false;await load()}
  catch(e){if(epoch===generation&&token===user.token&&props.modelValue)error.value=e instanceof Error?e.message:'品牌确认失败；请重新核对后再试'}
  finally{if(epoch===generation)busy.value=false}
}
watch(()=>props.modelValue,open=>{generation++;preview.value=undefined;busy.value=false;loading.value=false;error.value='';success.value='';keyword.value='';tab.value='ready';page.value=1;if(open){reason.value='按品牌别名和原型号明确前缀批量核对品牌';void load()}},{immediate:true})
watch([tab,keyword],()=>page.value=1)
watch([()=>user.token,()=>props.editable],()=>{generation++;preview.value=undefined;busy.value=false;loading.value=false;success.value='';error.value='';reason.value='';visible.value=false})
onBeforeUnmount(()=>generation++)
</script>
<template>
  <el-dialog v-model="visible" append-to-body :title='uiText("一键核对品牌")' width="min(1040px,96vw)" top="6vh" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" class="phone-brand-review">
    <section class="brand-review-content" v-loading="loading">
      <p class="brand-review-scope">{{ uiText("核对全库未归类编码组，包含其他分页和当前筛选以外的记录。仅保存品牌；型号原文、手机编码、生产状态及模具适配分别维护。") }}</p>
      <el-alert v-if="success" :title="success" type="success" :closable="false"/>
      <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link :disabled="busy" @click="load">{{ uiText("重新核对") }}</el-button></el-alert>
      <template v-if="preview">
        <div class="brand-review-summary"><strong>{{ uiText("未归类 ") }}{{preview.total}}{{ uiText(" 个 · 可确认 ") }}{{preview.candidateCount}}{{ uiText(" 个 · 保留未归类 ") }}{{preview.unresolvedCount}}{{ uiText(" 个") }}</strong><el-button size="small" :disabled="loading||busy" @click="load">{{ uiText("重新核对") }}</el-button></div>
        <p v-if="distribution" class="brand-review-distribution">{{distribution}}</p>
        <div class="brand-review-tools"><el-radio-group v-model="tab" size="small" :aria-label='uiText("品牌核对结果")'><el-radio-button value="ready">{{ uiText("可一键确认 (") }}{{preview.candidateCount}})</el-radio-button><el-radio-button value="unresolved">{{ uiText("保留未归类 (") }}{{preview.unresolvedCount}})</el-radio-button></el-radio-group><el-input v-model="keyword" size="small" clearable :placeholder='uiText("查编码、原型号、品牌或原因")' :aria-label='uiText("品牌核对搜索")'/></div>
        <p v-if="tab==='unresolved'&&preview.unresolvedCount" class="brand-review-scope">{{ uiText("通用配件、结构件或服务可保持品牌空白，无需逐条确认；需要按具体手机查询时，再集中补适用设备或来源规格。确需补充品牌时，可在手机编码 → 品牌维护别名或前缀，或点“定位编码”后在“维护”中指定品牌。") }}</p>
        <div class="brand-review-records" role="table" :aria-label='uiText("品牌核对预览")'><div class="brand-review-heading brand-review-row" role="row"><span role="columnheader">{{ uiText("编码") }}</span><span role="columnheader">{{ uiText("原型号名称") }}</span><span role="columnheader">{{ uiText("品牌") }}</span><span role="columnheader">{{tab==='ready'?uiText('核对依据'):uiText('保留原因／补充位置')}}</span></div><div class="brand-review-body" role="rowgroup"><div v-for="item in records" :key="item.id" class="brand-review-row" role="row"><strong role="cell">{{item.codeValue}}</strong><span role="cell">{{item.codeName}}</span><span role="cell">{{item.brandNames.join(' / ')||uiText('未归类')}}</span><span role="cell">{{[item.reason,...item.evidence].filter(Boolean).join('；')}}<el-button v-if="item.state==='unresolved'" link size="small" :disabled="busy" @click="emit('locate',item.codeValue)">{{ uiText("定位编码") }}</el-button></span></div><el-empty v-if="!records.length" :description="keyword?uiText('没有符合搜索的记录'):tab==='ready'?uiText('没有需要批量确认的品牌'):uiText('没有保留未归类的记录')" :image-size="48"/></div></div>
        <el-pagination v-model:current-page="page" size="small" :page-size="50" :total="filtered.length" :pager-count="5" layout="total,prev,pager,next"/>
        <el-input v-if="candidates.length" v-model="reason" size="small" :aria-label='uiText("批量品牌核对原因")' maxlength="1000" :placeholder='uiText("核对原因（已按本次依据填写，可修改）")' :disabled="busy"/>
      </template>
    </section>
    <template #footer><el-button size="small" :disabled="busy" @click="visible=false">{{ uiText("关闭") }}</el-button><el-button size="small" type="primary" :loading="busy" :disabled="!canApply" @click="apply">{{ uiText("一键确认全部明确品牌") }}{{candidates.length?` (${candidates.length})`:''}}</el-button></template>
  </el-dialog>
</template>
<style>
.phone-brand-review{--el-font-size-base:12px;--el-dialog-title-font-size:16px}.phone-brand-review .el-dialog__body{display:flex;max-height:72dvh;overflow:hidden}.phone-brand-review .el-dialog__title{font-size:16px}.phone-brand-review .el-button,.phone-brand-review .el-radio-button__inner{font-size:12px;min-height:28px}.phone-brand-review .el-dialog__footer{padding-top:8px}
</style>
<style scoped>
.brand-review-content{display:flex;flex:1;flex-direction:column;min-height:0;min-width:0;gap:8px;font-size:12px;container-type:inline-size}.brand-review-scope,.brand-review-distribution{margin:0;line-height:18px;color:var(--el-text-color-secondary);overflow-wrap:anywhere}.brand-review-summary,.brand-review-tools{display:flex;align-items:center;gap:8px;flex:none;justify-content:space-between;flex-wrap:wrap}.brand-review-tools .el-input{flex:1;min-width:180px}.brand-review-records{display:flex;flex-direction:column;min-height:120px;max-height:42dvh;overflow:hidden;border:1px solid var(--el-border-color-lighter);border-radius:4px}.brand-review-body{min-height:0;overflow-y:auto;overflow-x:hidden}.brand-review-row{display:grid;grid-template-columns:58px minmax(0,1.3fr) minmax(80px,.6fr) minmax(0,1.5fr);gap:8px;align-items:center;min-height:40px;padding:6px 8px;line-height:18px;box-sizing:border-box;border-bottom:1px solid var(--el-border-color-lighter)}.brand-review-row>*{min-width:0;overflow-wrap:anywhere}.brand-review-heading{min-height:28px;flex:none;background:var(--el-fill-color-light);color:var(--el-text-color-secondary)}.el-pagination{justify-content:flex-end;flex:none}
@container(max-width:600px){.brand-review-heading{display:none}.brand-review-row{grid-template-columns:48px minmax(0,1fr);gap:4px 8px}.brand-review-row>*:nth-child(3),.brand-review-row>*:nth-child(4){grid-column:2}.brand-review-tools .el-input{flex-basis:100%}.brand-review-summary strong{font-weight:500}}
</style>
