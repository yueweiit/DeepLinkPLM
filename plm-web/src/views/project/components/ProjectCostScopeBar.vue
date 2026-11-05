<script setup lang="ts">
import {computed,reactive,ref} from 'vue'
import {ElMessageBox} from 'element-plus'
import {t as uiText} from '@/locales'
import PhoneModelMultiSelect from '@/components/PhoneModelMultiSelect/index.vue'
import {researchAction,type ProductResearchDetail,type DevelopmentCostScope} from '@/api/modules/productResearch'
const props=defineProps<{detail:ProductResearchDetail;selected:string;writable:boolean;disabled?:boolean}>()
const emit=defineEmits<{choose:[string];updated:[ProductResearchDetail]}>()
const scopes=computed(()=>(props.detail.workflow.costScopes||[]) as DevelopmentCostScope[])
const current=computed(()=>scopes.value.find(s=>s.id===props.selected))
const editingScope=computed(()=>scopes.value.find(s=>s.id===form.id))
const open=ref(false),busy=ref(false),error=ref(''),requestId=ref('')
const form=reactive({id:'',name:'',phoneModelIds:[] as number[],source:''})
const sources=computed(()=>((props.detail as any).applications||[]).flatMap((a:any)=>(a.engineeringPlan?.specifications||[]).map((s:any)=>({key:`${a.applicationId}:${s.key}`,applicationId:a.applicationId,specKey:s.key,name:`${a.approvalNo||a.applicationId} · ${s.holeType||''} ${s.structureDescription||''}`,phoneModelIds:s.phoneModelIds||[]}))))
function edit(scope?:DevelopmentCostScope){if(props.disabled)return;form.id=scope?.id||'';form.name=scope?.name||'';form.phoneModelIds=[...scope?.phoneModelIds||[]];form.source='';requestId.value=crypto.randomUUID();open.value=true;error.value=''}
function sourceChanged(key:string){const source=sources.value.find((s:any)=>s.key===key);if(source)form.phoneModelIds=[...source.phoneModelIds]}
async function action(kind:'unit_cost_group_save'|'unit_cost_group_status'|'unit_cost_group_delete',data:object){
 if(busy.value||props.disabled)return;busy.value=true;error.value=''
 try{const result=await researchAction(props.detail.productId,kind,props.detail.workflow.version,data,requestId.value||crypto.randomUUID());const oldIds=scopes.value.map(s=>s.id);emit('updated',result);open.value=false;if(kind==='unit_cost_group_delete')emit('choose','');else if(kind==='unit_cost_group_save'){const added=((result.workflow.costScopes||[]) as DevelopmentCostScope[]).find(s=>s.id===form.id||!oldIds.includes(s.id));if(added)emit('choose',added.id)}}
 catch(e){error.value=e instanceof Error?e.message:String(e)}finally{busy.value=false}
}
async function save(){const source=sources.value.find((s:any)=>s.key===form.source);await action('unit_cost_group_save',{costScopeId:form.id,name:form.name,phoneModelIds:form.phoneModelIds,...(source?{applicationId:source.applicationId,specKey:source.specKey}:{})})}
async function remove(){try{await ElMessageBox.confirm(uiText('删除未使用的空成本组？已有核算或费用的组请停用。'),uiText('删除成本组'));await action('unit_cost_group_delete',{costScopeId:form.id})}catch{}}
defineExpose({dirty:computed(()=>open.value),busy})
</script>
<template>
<section class="cost-scope-bar" :aria-label="uiText('开发规格成本组')"><div class="scope-toolbar"><label>{{uiText('开发规格')}}</label><el-select :empty-values="[null,undefined]" :model-value="selected" :disabled="disabled||busy" :aria-label="uiText('开发规格成本组')" @change="emit('choose',$event)"><el-option value="" :label="uiText('待归属')"/><el-option value="shared" :label="uiText('项目共用费用')"/><el-option v-for="scope in scopes" :key="scope.id" :value="scope.id" :label="scope.name+(scope.inactive?' · '+uiText('已停用'):'')"/></el-select><el-button v-if="writable" :disabled="disabled||busy" @click="edit()">{{uiText('新增成本组')}}</el-button><el-button v-if="writable&&current" :disabled="disabled||busy" @click="edit(current)">{{uiText('维护本组')}}</el-button></div>
<p v-if="current" class="muted">{{current.phoneModelIds.length}} {{uiText('个兼容手机')}}</p>
<form v-if="open" class="scope-form" @submit.prevent="save"><label><strong>{{uiText('开发规格名称')}} *</strong><el-input v-model="form.name" :disabled="busy||editingScope?.inactive" :aria-label="uiText('开发规格名称')" :placeholder="uiText('如：Pro、Pro Max、Duo')"/></label><label v-if="!form.id&&sources.length"><strong>{{uiText('已有开发规格（选填）')}}</strong><el-select v-model="form.source" clearable :disabled="busy" :aria-label="uiText('已有开发规格')" @change="sourceChanged"><el-option v-for="source in sources" :key="source.key" :value="source.key" :label="source.name"/></el-select></label><label><strong>{{uiText('本组兼容手机（可后补）')}}</strong><PhoneModelMultiSelect v-model="form.phoneModelIds" :disabled="busy||editingScope?.inactive" :aria-label="uiText('本组兼容手机')"/></label><p v-if="error" role="alert">{{error}}</p><div class="scope-actions"><el-button :disabled="busy" @click="open=false">{{uiText('取消')}}</el-button><el-button v-if="!editingScope?.inactive" type="primary" native-type="submit" :loading="busy" :disabled="!form.name.trim()">{{uiText('保存成本组')}}</el-button><template v-if="form.id"><el-button :disabled="busy" @click="action('unit_cost_group_status',{costScopeId:form.id,inactive:!editingScope?.inactive})">{{uiText(editingScope?.inactive?'恢复成本组':'停用成本组')}}</el-button><el-button type="danger" link :disabled="busy" @click="remove">{{uiText('删除空组')}}</el-button></template></div></form><p v-else-if="error" role="alert">{{error}}</p>
</section>
</template>
<style scoped>
.cost-scope-bar{background:var(--el-bg-color);border:1px solid var(--el-border-color-light);border-radius:6px;padding:12px}.scope-toolbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.scope-toolbar>label{font-weight:700}.scope-toolbar>.el-select{width:240px;max-width:100%}.scope-form{display:flex;flex-direction:column;gap:12px;padding-top:12px;border-top:1px solid var(--el-border-color-light);margin-top:12px}.scope-form>label{display:flex;flex-direction:column;gap:6px}.scope-form strong{background:var(--el-fill-color-light);padding:6px 8px;color:var(--el-text-color-primary)}.scope-actions{display:flex;gap:8px;flex-wrap:wrap}.scope-actions .el-button{margin:0}.muted{font-size:12px;color:var(--el-text-color-secondary);margin:8px 0 0}[role=alert]{color:var(--el-color-danger)}
</style>
