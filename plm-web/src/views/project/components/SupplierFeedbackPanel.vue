<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, reactive, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import type { DevelopmentMaterial, ResearchSupplierOption, SupplierAssignment, SupplierFeedback, SupplierFeedbackInput, SupplierFeedbackResult, MaterialReference } from '@/api/modules/productResearch';
import DocumentPreview from '@/views/file/components/DocumentPreview.vue';
import { formatDateTime } from '@/utils/format';
const props=defineProps<{records:SupplierFeedback[];suppliers:ResearchSupplierOption[];materials:DevelopmentMaterial[];assignments:SupplierAssignment[];editable:boolean;busy:boolean;context:string;error?:string;defaultSupplierCode?:string;restricted?:boolean;nextSupplierPending?:boolean}>();
const emit=defineEmits<{save:[SupplierFeedbackInput,string];upload:[];view:[DevelopmentMaterial];download:[DevelopmentMaterial]}>();
const open=ref(false),mode=ref<'record'|'correct'|'void'>('record'),openedContext=ref(''),openedEvent=ref(0),referencesRestricted=ref(false),baseline=ref(''),validation=ref('');
const form=reactive<SupplierFeedbackInput & {supplierCode:string;result:SupplierFeedbackResult;reason:string;references:MaterialReference[]}>({supplierCode:'',result:'pending',reason:'',scope:'',feedbackDate:null,references:[],releaseCurrent:false});
const releaseTarget=ref('');
const files=computed(()=>props.materials.filter(m=>m.type==='file'));
const matchingAssignments=computed(()=>props.assignments.filter(a=>a.mode==='supplier'&&a.supplierCode===form.supplierCode));
const allowed=computed(()=>props.editable&&openedContext.value===props.context&&(!form.feedbackId||props.records.find(r=>r.feedbackId===form.feedbackId)?.eventId===openedEvent.value));
const dirty=computed(()=>open.value&&JSON.stringify(form)!==baseline.value);
const resultName=(r:SupplierFeedbackResult)=>({pending:'待反馈',can_accept:'可以承接',cannot_accept:'无法承接'})[r];
const targetKey=(a:SupplierAssignment)=>JSON.stringify([a.role,a.scope]);
const roleName=(role:SupplierAssignment['role'])=>({mold:'开模',component:'部件',processing:'加工',finished_product:'成品'})[role];
function start(row?:SupplierFeedback,voiding=false){
 if(!props.editable||props.busy||row?.voided)return;
 mode.value=voiding?'void':row?'correct':'record';openedContext.value=props.context;openedEvent.value=row?.eventId||0;referencesRestricted.value=!!row?.referencesRestricted;validation.value='';releaseTarget.value='';
 Object.keys(form).forEach(k=>delete (form as unknown as Record<string,unknown>)[k]);
 Object.assign(form,{supplierCode:row?.supplierCode||row?.supplierSnapshot.supplierCode||props.defaultSupplierCode||'',result:row?.result||'pending',scope:row?.scope||'',feedbackDate:row?.feedbackDate||null,reason:row?.reason||'',references:(row?.references||[]).map(m=>({type:'file',id:m.id})),releaseCurrent:false,...(row?{feedbackId:row.feedbackId,changeReason:''}:{}),...(voiding?{voided:true}:{})});
 baseline.value=JSON.stringify(form);open.value=true;
}
watch(()=>[form.supplierCode,form.result],()=>{form.releaseCurrent=false;releaseTarget.value='';},{flush:'sync'});
async function leave(){
 if(!open.value)return true;
 if(props.busy)return false;
 if(dirty.value){try{await ElMessageBox.confirm(uiText('供应商反馈尚未保存，放弃本次输入？'),uiText('未保存供应商反馈'),{confirmButtonText:uiText('放弃输入'),cancelButtonText:uiText('继续填写')});}catch{return false;}}
 open.value=false;return true;
}
async function beforeClose(done:()=>void){if(await leave())done();}
function submit(){
 validation.value='';
 if(!allowed.value){validation.value='项目、阶段或登录权限已变化，请关闭后重新打开反馈窗口';return;}
 if(!form.supplierCode){validation.value='请选择反馈供应商';return;}
 if(form.result==='cannot_accept'&&!form.reason.trim()){validation.value='请填写无法承接原因';return;}
 if(mode.value!=='record'&&!form.changeReason?.trim()){validation.value=mode.value==='void'?'请填写作废原因':'请填写更正原因';return;}
 const target=matchingAssignments.value.find(a=>targetKey(a)===releaseTarget.value);
 if(form.releaseCurrent&&!target){validation.value='请选择要结束的当前供应商分工';return;}
 const input:SupplierFeedbackInput=mode.value==='void'?{feedbackId:form.feedbackId,voided:true,changeReason:form.changeReason?.trim()}:{...form,reason:form.reason.trim(),references:form.references.map(m=>({type:'file',id:m.id})),...(form.releaseCurrent&&target?{previousSupplierCode:target.supplierCode,role:target.role,assignmentScope:target.scope}:{})};
 if(referencesRestricted.value&&mode.value==='correct')delete input.references;
 emit('save',input,openedContext.value);
}
function saved(){open.value=false;baseline.value='';validation.value='';}
function refKey(id:number){return files.value.find(m=>m.id===id);}
function updateReferences(ids:number[]){form.references=ids.map(id=>({type:'file',id}));}
function resolved(m:DevelopmentMaterial){return props.materials.find(v=>v.type==='file'&&v.id===m.id)||m;}
function isImage(m:DevelopmentMaterial){return !!m.attachment&&['jpg','jpeg','png','webp'].includes(m.attachment.fileExt?.toLowerCase());}
defineExpose({leave,saved,dirty});
</script>
<template>
 <section class="supplier-feedback work-section" :aria-label='uiText("供应商反馈")'>
  <header class="section-heading"><h3>{{ uiText("供应商反馈") }}</h3><span v-if="nextSupplierPending" class="pending-supplier-status">{{ uiText("开模供应商：下一家待定") }}</span><el-button v-if="editable" :disabled="busy" @click="start()">{{ uiText("记录反馈") }}</el-button></header>
  <p v-if="restricted" class="muted">{{ uiText("部分供应商反馈无查看权限。") }}</p><p v-else-if="!records.length" class="muted">{{ uiText("暂无反馈记录") }}</p>
  <article v-for="row in records" :key="row.feedbackId" class="feedback-record" :class="{'is-void':row.voided}">
   <header class="feedback-heading"><strong>{{row.supplierSnapshot.supplierName}} · {{row.supplierSnapshot.supplierCode}}</strong><el-tag :type="row.voided?'info':row.result==='cannot_accept'?'warning':row.result==='can_accept'?'success':'info'" size="small">{{row.voided?uiText('已作废'):resultName(row.result)}}</el-tag><span v-if="row.scope">{{row.scope}}</span><span class="muted">{{ uiText("反馈日期：") }}{{row.feedbackDate||uiText('未填写')}}</span></header>
   <p v-if="row.reason" class="feedback-reason">{{row.reason}}</p>
   <div v-for="m in row.references" :key="m.id" class="feedback-file"><DocumentPreview v-if="isImage(resolved(m))" :file="resolved(m).attachment!" compact/><span>{{resolved(m).name||uiText('附件 #{value0}',{value0:(m.id)})}} · {{resolved(m).versionNo||uiText('版本待核对')}}</span><el-button link :disabled="busy" @click="emit('view',resolved(m))">{{ uiText("预览") }}</el-button><el-button link :disabled="busy" @click="emit('download',resolved(m))">{{ uiText("下载") }}</el-button></div>
   <div class="feedback-actions"><span class="muted">{{row.recordedBy}} · {{formatDateTime(row.recordedAt)}}{{ uiText(" · 第 ") }}{{row.revision}}{{ uiText(" 版") }}</span><el-button v-if="editable&&!row.voided" link :disabled="busy" @click="start(row)">{{ uiText("修改反馈") }}</el-button><el-button v-if="editable&&!row.voided" link type="danger" :disabled="busy" @click="start(row,true)">{{ uiText("作废反馈") }}</el-button></div>
   <details class="feedback-history"><summary>{{ uiText("查看完整记录及更正历史（") }}{{row.history?.length||0}}）</summary><p>{{ uiText("记录 #") }}{{row.feedbackId}}{{ uiText(" · 录入步骤 ") }}{{row.stage}}{{ uiText(" · 供应商资料版本 ") }}{{row.supplierSnapshot.version??uiText('未记录')}}</p><p v-if="row.changeReason">{{ uiText("本次") }}{{row.voided?uiText('作废'):uiText('更正')}}{{ uiText("原因：") }}{{row.changeReason}}</p><article v-for="h in row.history||[]" :key="h.eventId"><strong>{{ uiText("第 ") }}{{h.revision}}{{ uiText(" 版 · ") }}{{h.voided?uiText('已作废'):resultName(h.result)}}</strong><p>{{h.supplierSnapshot.supplierName}} · {{h.scope}} · {{h.feedbackDate||uiText('反馈日期未填写')}}</p><p>{{h.reason}}</p><p v-if="h.changeReason">{{ uiText("更正／作废原因：") }}{{h.changeReason}}</p><p class="muted">{{h.recordedBy}} · {{h.recordedAt}}</p><div v-for="m in h.references" :key="m.id" class="feedback-file"><span>{{resolved(m).name||uiText('附件 #{value0}',{value0:(m.id)})}} · {{resolved(m).versionNo||uiText('版本待核对')}}</span><el-button link @click="emit('view',resolved(m))">{{ uiText("预览") }}</el-button><el-button link @click="emit('download',resolved(m))">{{ uiText("下载") }}</el-button></div></article></details>
  </article>
  <el-dialog v-model="open" class="research-entry-dialog supplier-feedback-dialog" :title="mode==='void'?uiText('作废供应商反馈'):mode==='correct'?uiText('更正供应商反馈'):uiText('记录供应商反馈')" width="min(680px,94vw)" :before-close="beforeClose" :close-on-click-modal="false">
   <el-alert v-if="!allowed" :title='uiText("项目、阶段或登录权限已变化；保留输入供核对，请重新打开后办理。")' type="warning" :closable="false"/>
   <el-form label-position="top" :disabled="!allowed||busy||mode==='void'">
    <el-form-item :label='uiText("反馈供应商")' required><el-select v-model="form.supplierCode" filterable :aria-label='uiText("反馈供应商")' :disabled="mode!=='record'" :placeholder='uiText("选择已有供应商")'><el-option v-for="s in suppliers" :key="s.supplierCode" :value="s.supplierCode" :label="`${s.supplierName} · ${s.supplierCode}`" :disabled="s.status==='inactive'||s.needsReview"/></el-select></el-form-item>
    <el-form-item :label='uiText("反馈结果")' required><el-radio-group v-model="form.result" :aria-label='uiText("反馈结果")'><el-radio value="pending">{{ uiText("待反馈") }}</el-radio><el-radio value="can_accept">{{ uiText("可以承接") }}</el-radio><el-radio value="cannot_accept">{{ uiText("无法承接") }}</el-radio></el-radio-group></el-form-item>
    <el-form-item :label='uiText("沟通／制作范围")'><el-input v-model="form.scope" maxlength="2000" :aria-label='uiText("反馈范围")' :placeholder='uiText("例如开模、PC背板、装配；未知留空")'/></el-form-item>
    <el-form-item :label='uiText("反馈日期")'><el-date-picker v-model="form.feedbackDate" value-format="YYYY-MM-DD" clearable :placeholder='uiText("未知留空")' :aria-label='uiText("反馈日期")'/></el-form-item>
    <el-form-item :label="form.result==='cannot_accept'?uiText('无法承接原因'):uiText('反馈说明')" :required="form.result==='cannot_accept'"><el-input v-model="form.reason" maxlength="2000" type="textarea" :autosize="{minRows:3}" :aria-label='uiText("反馈说明")'/></el-form-item>
    <el-form-item :label='uiText("本项目反馈附件")'><el-select :model-value="form.references.map(m=>m.id)" multiple filterable :disabled="referencesRestricted" :aria-label='uiText("反馈附件")' :placeholder='uiText("按文件名搜索本项目已上传版本")' @update:model-value="updateReferences"><el-option v-for="m in files" :key="m.id" :value="m.id" :label="`${m.name} · ${m.versionNo}`"/></el-select><div v-for="r in form.references" :key="r.id" class="feedback-file"><span>{{refKey(r.id)?.name||uiText('附件 #{value0}',{value0:(r.id)})}} · {{refKey(r.id)?.versionNo||uiText('版本待核对')}}</span><el-button v-if="refKey(r.id)" link @click="emit('view',refKey(r.id)!)">{{ uiText("预览") }}</el-button></div><p v-if="referencesRestricted" class="muted">{{ uiText("部分附件无查看权限，更正会保留原附件关系。") }}</p><el-button v-if="!referencesRestricted" link :disabled="busy" @click="emit('upload')">{{ uiText("从本地上传反馈图片／附件") }}</el-button></el-form-item>
    <template v-if="mode==='record'&&form.result==='cannot_accept'&&matchingAssignments.length"><el-form-item :label='uiText("当前供应商对接")'><el-checkbox v-model="form.releaseCurrent">{{ uiText("结束当前供应商对接，下一家待定") }}</el-checkbox></el-form-item><el-form-item v-if="form.releaseCurrent" :label='uiText("结束的当前供方分工")' required><el-select v-model="releaseTarget" :aria-label='uiText("结束的供方分工")' :placeholder='uiText("明确选择本次结束的范围")'><el-option v-for="a in matchingAssignments" :key="targetKey(a)" :value="targetKey(a)" :label="`${roleName(a.role)} · ${a.scope}`"/></el-select></el-form-item></template>
   </el-form>
   <el-form v-if="mode!=='record'" label-position="top" :disabled="!allowed||busy" class="feedback-correction"><el-form-item :label="mode==='void'?uiText('作废原因'):uiText('更正原因')" required><el-input v-model="form.changeReason" maxlength="2000" type="textarea" autosize :aria-label='uiText("反馈更正原因")'/></el-form-item></el-form>
   <el-alert v-if="validation||error" :title="validation||error" type="error" :closable="false"/>
   <template #footer><el-button :disabled="busy" @click="leave">{{ uiText("取消") }}</el-button><el-button :disabled="!allowed" :loading="busy" type="primary" @click="submit">{{mode==='void'?uiText('确认作废'):uiText('保存反馈')}}</el-button></template>
  </el-dialog>
 </section>
</template>
<style scoped>
.supplier-feedback{min-width:0;font-size:12px}.section-heading,.feedback-heading,.feedback-actions,.feedback-file{display:flex;align-items:center;flex-wrap:wrap;gap:6px 10px}.section-heading{justify-content:space-between;margin-bottom:12px}.section-heading h3{margin:0;font-size:14px;font-weight:700}.feedback-record{min-width:0;padding:12px 0;border-bottom:1px solid var(--el-border-color-lighter);overflow-wrap:anywhere}.feedback-reason,.feedback-history p{white-space:pre-wrap;margin:6px 0;overflow-wrap:anywhere}.feedback-heading strong{font-size:12px}.feedback-file{margin:6px 0}.feedback-file>span{min-width:0;overflow-wrap:anywhere}.feedback-file :deep(.document-preview--compact){width:80px;height:64px;flex:none}.feedback-actions{margin-top:6px}.feedback-actions>.muted{margin-right:auto}.feedback-history{margin-top:6px}.feedback-history summary{cursor:pointer;color:var(--el-color-primary)}.feedback-history article{padding:8px 12px;margin-top:6px;border-left:2px solid var(--el-border-color)}.muted{color:var(--el-text-color-secondary)}.is-void{color:var(--el-text-color-secondary)}.feedback-correction{margin-top:12px}
</style>
