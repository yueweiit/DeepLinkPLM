<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {getPhoneModelReview,confirmPhoneModelReview,type PhoneModelReviewKind,type PhoneModelReviewRow,type PhoneModelReviewConclusion} from '@/api/modules/code'
import {useCachedRequest} from '@/composables/useCachedRequest'
import {useUserStore} from '@/stores/user'
import {useCollaborationWorkspace} from '@/stores/collaborationWorkspace'
import SharedCompatiblePhoneSelector from './SharedCompatiblePhoneSelector.vue'
import FixedTableViewport from '@/components/FixedTableViewport/index.vue'
const props=defineProps<{keyword?:string;initialKind?:PhoneModelReviewKind;initialReviewState?:'pending'|'confirmed'|''}>()
const emit=defineEmits<{navigate:[]}>()
const user=useUserStore(),workspace=useCollaborationWorkspace(),router=useRouter(),keyword=ref(props.keyword||''),kind=ref<PhoneModelReviewKind>(props.initialKind||'code_many_names'),page=ref(1),reviewState=ref<'pending'|'confirmed'|''>(props.initialReviewState??'pending')
const request=useCachedRequest(getPhoneModelReview),rows=computed(()=>request.hasCurrentData.value?request.data.value?.content||[]:[]),total=computed(()=>request.data.value?.totalElements||0)
const reviewing=ref(false),active=ref<PhoneModelReviewRow>(),activeKind=ref<PhoneModelReviewKind>('code_many_names'),reason=ref(''),conclusion=ref<PhoneModelReviewConclusion>(),phoneIds=ref<number[]>([]),projectIds=ref<number[]>([]),saving=ref(false),saveError=ref('')
const conclusions:Record<PhoneModelReviewConclusion,string>={same_phone_alias:'同一手机的不同名称／别名',shared_code_distinct_phones:'不同手机共用一个编码',shared_mold_different_codes:'不同编码的历史共模关系',combined_original_preserved:'组合型号原文保留，不拆分'}
const canLinkProjects=computed(()=>conclusion.value==='same_phone_alias'&&phoneIds.value.length===1)
function message(e:unknown){return e instanceof Error?e.message:'保存失败，请重试'}
function load(force=false){return request.execute({kind:kind.value,page:page.value,size:50,...(keyword.value.trim()?{keyword:keyword.value.trim()}:{}),...(reviewState.value?{reviewState:reviewState.value}:{})},force)}
function search(){page.value=1;void load()}
function open(row:PhoneModelReviewRow){active.value=row;activeKind.value=kind.value;reason.value=row.reviewReason||'';conclusion.value=row.conclusion;phoneIds.value=[...(row.reviewPhoneModelIds||[])];projectIds.value=[];saveError.value='';reviewing.value=true}
async function save(){
  const row=active.value,token=user.token
  if(!row?.canReview||!row.sourceDigest){saveError.value='当前账号不能确认这组资料，请由具备工程资料维护权限的人员处理';return}
  if(!conclusion.value||!reason.value.trim()){saveError.value='请选择核对结论并填写依据';return}
  if(projectIds.value.length&&!canLinkProjects.value){saveError.value='补关联项目时，须明确同一手机并只选择一款标准手机';return}
  const links=(row.references||[]).filter(p=>projectIds.value.includes(p.projectId)).map(p=>({projectId:p.projectId,originalPhoneModelId:p.phoneModelId,phoneModelId:phoneIds.value[0]!}))
  saving.value=true;saveError.value=''
  try{await confirmPhoneModelReview({kind:activeKind.value,key:row.key,sourceDigest:row.sourceDigest,conclusion:conclusion.value,reason:reason.value.trim(),phoneModelIds:phoneIds.value,...(links.length?{projectLinks:links}:{})});if(token!==user.token)return;workspace.changed();reviewing.value=false;await load(true);ElMessage.success('历史关系核对已保存；实物与适配仍分别维护')}
  catch(e){if(token===user.token)saveError.value=message(e)}finally{saving.value=false}
}
function viewProject(id:number){reviewing.value=false;emit('navigate');void router.push({path:'/projects',query:{productId:id}})}
watch([kind,reviewState],search)
watch(()=>props.keyword,v=>{keyword.value=v||'';search()})
watch(()=>props.initialKind,v=>{if(v)kind.value=v})
watch(()=>props.initialReviewState,v=>{reviewState.value=v??'pending'})
watch(canLinkProjects,v=>{if(!v)projectIds.value=[]})
watch(()=>user.token,()=>{request.clear();request.data.value=undefined;request.hasCurrentData.value=false;reviewing.value=false;active.value=undefined},{flush:'sync'})
onMounted(()=>void load())
</script>
<template>
  <section class="phone-code-review" aria-label="手机历史关系核对">
    <p class="hint">核对历史名称差异，保存结论和依据。原型号与刻码保留；“已核对”不表示实物、适配或可复用已确认。</p>
    <div class="tools"><el-select v-model="kind" aria-label="历史关系核对类别"><el-option value="code_many_names" label="同码多名称"/><el-option value="name_many_codes" label="同名称多编码"/></el-select><el-select v-model="reviewState" aria-label="历史核对状态"><el-option value="pending" label="待核对"/><el-option value="confirmed" label="已核对"/><el-option value="" label="全部"/></el-select><el-input v-model="keyword" placeholder="搜索历史型号名称 / 编码" clearable @keyup.enter="search"/><el-button @click="search">查询</el-button><el-button @click="load(true)">刷新</el-button></div>
    <el-alert v-if="request.error.value" :title="request.error.value" type="error" :closable="false"/>
    <FixedTableViewport fill :refresh-key="rows"><div class="review-list" role="table" aria-label="历史手机关系记录" v-loading="request.loading.value"><div class="review-row review-heading" role="row"><span role="columnheader">原编码</span><span role="columnheader">历史手机型号名称</span><span role="columnheader">引用数</span><span role="columnheader">处理状态</span><span role="columnheader">操作</span></div><div class="review-body" role="rowgroup" tabindex="0"><div v-for="row in rows" :key="row.key" class="review-row review-record" role="row"><div class="values review-code" role="cell">{{row.codeValues.join('\n')}}</div><div class="values review-name" role="cell">{{row.modelNames.join('\n')}}</div><div class="review-count" role="cell">{{row.sourceCount}}<span class="compact-label"> 条引用</span></div><div class="review-state" role="cell">{{row.reviewState==='confirmed'?'已核对':'待核对'}}<small v-if="row.reviewedBy">{{row.reviewedBy}}</small></div><div class="review-action" role="cell"><el-button link @click="open(row)">{{row.canReview?(row.reviewState==='confirmed'?'查看／更正结论':'核对并保存'):'查看核对资料'}}</el-button></div></div><el-empty v-if="!rows.length&&!request.loading.value" description="没有符合条件的历史关系" :image-size="60"/></div></div></FixedTableViewport>
    <el-pagination v-model:current-page="page" :page-size="50" :total="total" layout="total,prev,pager,next" @current-change="load()"/>
    <el-dialog :key="user.token" v-model="reviewing" append-to-body title="核对历史手机关系" width="min(840px,96vw)" :close-on-click-modal="false" class="phone-history-review-dialog">
      <template v-if="active"><p>{{active.codeValues.join(' / ')}}</p><p class="values">{{active.modelNames.join('\n')}}</p>
        <el-alert v-if="!active.canReview" type="info" :closable="false" :title="active.reviewBlockReason||'当前账号可查看资料；确认结论需由具备工程资料维护权限的人员处理。'"/>
        <el-form label-position="top" :disabled="!active.canReview||saving"><el-form-item label="明确核对结论"><el-select v-model="conclusion" aria-label="历史核对结论" placeholder="请选择结论"><el-option v-for="(label,key) in conclusions" :key="key" :value="key" :label="label"/></el-select></el-form-item><el-form-item label="相关标准手机（可不选，组合型号不强迫拆分）"><SharedCompatiblePhoneSelector v-model="phoneIds" :disabled="!active.canReview||saving" :allow-manage="false"/></el-form-item><el-form-item label="核对依据"><el-input v-model="reason" type="textarea" :rows="3" aria-label="历史核对依据" maxlength="2000" placeholder="说明名称差异、共码／共模关系或保留组合原文的依据"/></el-form-item></el-form>
        <h4>来源项目及当前关联</h4><p class="hint">仅在明确“同一手机”且只选择一款标准手机时，可勾选单手机项目补关联；不改历史名称、编码或模具适配。</p>
        <div v-for="project in active.references" :key="project.projectId" class="reference-row"><el-checkbox v-if="canLinkProjects&&active.canReview&&project.productType==='model_variant'" :model-value="projectIds.includes(project.projectId)" :disabled="saving" @change="(v:boolean|string|number)=>projectIds=v?[...projectIds,project.projectId]:projectIds.filter(id=>id!==project.projectId)">明确此项目仅对应所选手机</el-checkbox><span>#{{project.projectId}} · {{project.model}} · {{project.phoneModelCode}}<small>当前：{{project.currentPhoneName||'标准手机尚未关联'}}</small></span><el-button link @click="viewProject(project.projectId)">查看项目</el-button></div>
        <el-alert v-if="saveError" :title="saveError" type="error" :closable="false"/>
      </template>
      <template #footer><el-button @click="reviewing=false">关闭</el-button><el-button v-if="active?.canReview" type="primary" :loading="saving" data-test="save-history-review" @click="save">保存核对结论</el-button></template>
    </el-dialog>
  </section>
</template>
<style scoped>
.phone-code-review{display:flex;flex:1;min-height:0;min-width:0;flex-direction:column;gap:8px;container-type:inline-size;font-size:12px;--el-font-size-base:12px}.hint{color:#748396;line-height:1.5;font-size:12px;margin:0;flex:none}.tools{display:flex;flex-wrap:wrap;gap:6px;flex:none}.tools .el-select{width:145px}.tools .el-input{flex:1;min-width:180px}.values{white-space:pre-wrap;overflow-wrap:anywhere}.el-pagination{justify-content:flex-end;flex:none}small{display:block;font-size:12px;color:#748396}.reference-row{display:flex;gap:8px;align-items:center;flex-wrap:wrap;border-bottom:1px solid #e6eaf0;padding:6px 0}.reference-row>span{flex:1;min-width:180px}.phone-code-review :deep(.el-button){height:28px;font-size:12px}.review-list{height:100%;display:flex;flex-direction:column;overflow:hidden;border:1px solid var(--el-border-color-lighter);border-radius:4px}.review-heading{background:var(--el-fill-color-light);color:var(--el-text-color-secondary);flex:none}.review-body{flex:1;min-height:0;overflow-y:auto;overflow-x:hidden;scrollbar-gutter:stable}.review-row{display:grid;grid-template-columns:minmax(65px,.7fr) minmax(0,4fr) 50px 85px 115px;gap:8px;align-items:center;padding:6px 8px;line-height:18px}.review-record{border-bottom:1px solid var(--el-border-color-lighter);min-height:40px;box-sizing:border-box}.review-record:nth-child(even){background:var(--el-fill-color-lighter)}.review-action .el-button{white-space:normal;text-align:left;line-height:18px;height:auto;min-height:28px}.compact-label{display:none}
@container(max-width:650px){.review-heading{display:none}.review-row{grid-template-columns:minmax(0,1fr) minmax(0,2fr);grid-template-areas:"code state" "name name" "count action";gap:4px 8px}.review-code{grid-area:code}.review-name{grid-area:name}.review-count{grid-area:count}.review-state{grid-area:state;text-align:right}.review-action{grid-area:action;justify-self:end}.compact-label{display:inline}.tools .el-select{flex:1;min-width:130px}.tools .el-input{flex-basis:100%}}
</style>
<style>.phone-history-review-dialog{font-size:12px;--el-font-size-base:12px}.phone-history-review-dialog .el-dialog__title{font-size:16px}.phone-history-review-dialog .el-button{height:28px;font-size:12px}.phone-history-review-dialog .el-select{width:100%}.phone-history-review-dialog .el-select__wrapper{min-height:28px}.phone-history-review-dialog .values{white-space:pre-wrap;overflow-wrap:anywhere}.phone-history-review-dialog .el-form-item__label{font-size:12px}</style>
