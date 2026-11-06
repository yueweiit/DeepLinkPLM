<script setup lang="ts">
import {computed,onBeforeUnmount,ref} from 'vue'
import {t as uiText} from '@/locales'
import {partnerError} from '@/utils/partnerError'
import {getProductResearchDetail,researchAction,type ProductResearchDetail,type DevelopmentCostScope,type UnitCostSheet} from '@/api/modules/productResearch'
const props=defineProps<{detail:ProductResearchDetail;sourceScopeId:string;writable:boolean}>()
const emit=defineEmits<{updated:[ProductResearchDetail];assigned:[string];cancel:[]}>()
const scopes=computed(()=>(props.detail.workflow.costScopes||[]) as DevelopmentCostScope[])
const source=computed(()=>props.sourceScopeId?scopes.value.find(s=>s.id===props.sourceScopeId):props.detail.workflow)
const draft=computed(()=>source.value?.unitCostDraft as UnitCostSheet|undefined)
const sourceName=computed(()=>props.sourceScopeId?scopes.value.find(s=>s.id===props.sourceScopeId)?.name:uiText('待归属'))
const target=ref<string|null>(null),busy=ref(false),error=ref('')
let active=true
onBeforeUnmount(()=>{active=false})
const attempt=ref<{version:number;data:{costScopeId:string;targetCostScopeId:string};requestId:string}|null>(null)
const targets=computed(()=>[{id:'',name:uiText('待归属'),occupied:!!props.detail.workflow.unitCostDraft},...scopes.value.filter(s=>!s.inactive).map(s=>({id:s.id,name:s.name,occupied:!!s.unitCostDraft}))].filter(s=>s.id!==props.sourceScopeId))
const money=(value:number|null|undefined)=>value==null?'—':Number(value).toLocaleString('zh-CN',{maximumFractionDigits:4})
async function save(){
 if(busy.value||!props.writable||!draft.value||target.value===null)return
 if(!attempt.value)attempt.value={version:props.detail.workflow.version,data:{costScopeId:props.sourceScopeId,targetCostScopeId:target.value},requestId:crypto.randomUUID()}
 const request=attempt.value,project=props.detail.productId;busy.value=true;error.value=''
 try{const result=await researchAction(project,'unit_cost_assign_scope',request.version,request.data,request.requestId);if(!active||props.detail.productId!==project||props.sourceScopeId!==request.data.costScopeId)return;emit('updated',result);emit('assigned',request.data.targetCostScopeId)}
 catch(e){if(active)error.value=uiText(partnerError(e))}finally{busy.value=false}
}
async function reload(){
 if(busy.value)return;const project=props.detail.productId,scope=props.sourceScopeId;busy.value=true;error.value=''
 try{const result=await getProductResearchDetail(project);if(!active||props.detail.productId!==project||props.sourceScopeId!==scope)return;emit('updated',result);attempt.value=null}
 catch(e){if(active)error.value=uiText(partnerError(e))}finally{busy.value=false}
}
defineExpose({busy,dirty:computed(()=>target.value!==null||!!attempt.value)})
</script>
<template>
<section class="cost-attribution-form" :aria-label="uiText('设置成本归属')">
 <h3>{{uiText('设置归属')}}</h3>
 <template v-if="draft">
  <div class="attribution-source"><strong>{{draft.title}}</strong><span>{{sourceName}} · {{uiText('已保存')}} · {{draft.currency}}／{{uiText('件')}}</span><span>{{uiText('单件生产')}} {{money(draft.totals?.manufacturing)}} · {{uiText('含摊销')}} {{money(draft.totals?.base)}}</span></div>
  <label><strong>{{uiText('归属到')}} *</strong><el-select v-model="target" :empty-values="[null,undefined]" :aria-label="uiText('归属到开发规格')" :placeholder="uiText('选择 Pro、Pro Max 等开发规格')" :disabled="busy||!writable||!!attempt"><el-option v-for="s in targets" :key="s.id" :value="s.id" :label="s.name+(s.occupied?' · '+uiText('已有草稿'):'')" :disabled="s.occupied"/></el-select></label>
  <p class="attribution-note">{{uiText('仅归属这份单件成本草稿；预算、费用和已确认版本保持原归属。')}}</p>
  <p v-if="!targets.length" class="attribution-note">{{uiText('请先在上方新增成本组。')}}</p>
 </template>
 <p v-else>{{uiText('当前范围没有可归属的成本草稿；已确认版本保留原归属。')}}</p>
 <p v-if="error" role="alert" class="attribution-error">{{error}}</p>
 <div class="attribution-actions"><el-button :disabled="busy" @click="emit('cancel')">{{uiText('取消')}}</el-button><el-button v-if="attempt" :disabled="busy" @click="reload">{{uiText('重新载入')}}</el-button><el-button v-if="draft" type="primary" :disabled="!writable||target===null||busy" :loading="busy" @click="save">{{uiText(attempt?'重试本次归属':'保存归属')}}</el-button></div>
</section>
</template>
<style scoped>
.cost-attribution-form{display:flex;flex-direction:column;gap:12px;padding:14px;border:1px solid var(--el-color-primary-light-5);border-radius:6px;background:var(--el-bg-color)}h3{margin:0;font-size:16px}.attribution-source{display:flex;flex-direction:column;gap:5px}.attribution-source>span,.attribution-note{font-size:12px;color:var(--el-text-color-secondary);margin:0}.cost-attribution-form>label{display:flex;flex-direction:column;gap:6px;max-width:620px}.cost-attribution-form>label>strong{color:var(--el-text-color-primary)}.attribution-actions{display:flex;gap:8px;flex-wrap:wrap}.attribution-actions .el-button{margin:0}.attribution-error{margin:0;color:var(--el-color-danger)}
</style>
