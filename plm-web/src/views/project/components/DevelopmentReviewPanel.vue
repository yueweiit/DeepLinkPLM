<script setup lang="ts">
import { partnerError as requestError } from "@/utils/partnerError";
import { computed, nextTick, reactive, ref, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { deleteDevelopmentDraft, getProductResearchDetail, materialKey, materialReferences, researchAction, saveDevelopmentRound, type ProductResearchDetail } from '@/api/modules/productResearch';
import ResearchSnapshot from './ResearchSnapshot.vue';

const props=defineProps<{detail:ProductResearchDetail;stage:number;owners:{userId:number;name:string}[]}>();
const emit=defineEmits<{updated:[ProductResearchDetail];view:[any]}>();
type Form=Record<string,any>;
const gate=ref('setup'),busy=ref(false),error=ref(''),editing=ref(false),changed=ref(false),historyOpen=ref(false),history=ref<unknown>(null);
const form=reactive<Form>({}),setup=reactive<Form>({tests:[],plannedQty:null,minYieldRate:null,fullInspection:null}),keys=ref<string[]>([]);
const panel=ref<HTMLElement|null>(null);
const state=computed(()=>props.detail.workflow);
const actualStage=computed(()=>state.value.revision?.stage||props.detail.currentStepNo||1);
const writable=computed(()=>props.detail.canWrite&&!['archived'].includes(props.detail.status)&&!['frozen','abandoned'].includes(props.detail.lockStatus||'')&&(props.detail.status!=='released'||!!state.value.revision));
const targetStage=computed(()=>({design:4,t0:6,reliability:6,pilot:7} as Record<string,number>)[gate.value]);
const canEdit=computed(()=>writable.value&&props.stage===actualStage.value&&(actualStage.value===targetStage.value||(gate.value==='design'&&actualStage.value===6)));
const canSetup=computed(()=>writable.value&&[6,7].includes(actualStage.value)&&props.stage===actualStage.value);
const needsMold=computed(()=>Boolean((state.value.plan as Form)?.needsMold));
const gates=computed(()=>props.stage===4?[{key:'design',label:'样品确认'}]:props.stage===6?[{key:'setup',label:'测试要求'},{key:'design',label:'方案复核'},...(needsMold.value?[{key:'t0',label:'试模确认'}]:[]),{key:'reliability',label:'验证结果'}]:[{key:'setup',label:'试产要求'},{key:'pilot',label:'试产结果'}]);
const gateName=computed(()=>gates.value.find(g=>g.key===gate.value)?.label||'评审');
const isRound=computed(()=>['reliability','pilot'].includes(gate.value));
const records=computed<Form[]>(()=>isRound.value?props.detail.rounds.filter(r=>r.kind===(gate.value==='reliability'?'test':'pilot')):((state.value.reviewRecords as Form[])||[]).filter(r=>r.gate===gate.value&&!r.deleted).slice().reverse());
const sources=computed(()=>props.detail.rounds.filter(r=>r.kind===(gate.value==='pilot'?'test':'sample')&&r.status==='confirmed'&&(gate.value==='t0'||(props.stage===4&&r.details?.samplePurpose==='prototype_3d')||r.result==='passed')&&!r.voided));
const molds=computed<Form[]>(()=>((props.detail as any).molds||[]));
const materials=computed(()=>props.detail.materials||[]);
function copy<T>(value:T):T{return JSON.parse(JSON.stringify(value));}
function assign(target:Form,value:Form){Object.keys(target).forEach(k=>delete target[k]);Object.assign(target,copy(value));}
watch([()=>props.detail.productId,()=>props.stage],()=>{assign(setup,{tests:[],plannedQty:null,minYieldRate:null,fullInspection:null,...(state.value.reviewSetup as Form||{})});editing.value=false;changed.value=false;gate.value=gates.value[0]!.key;},{immediate:true});
function sourceChanged(id:number){const source=sources.value.find(r=>r.roundId===id);if(!source)return;form.details.phoneModelIds=copy(source.details?.phoneModelIds||[]);keys.value=source.references.map(materialKey);changed.value=true;}
function newRecord(previous?:Form){
 const prior=previous?copy(previous):{};
 assign(form,{id:null,roundId:null,title:gateName.value,reviewDate:null,recordDate:null,reviewerUserId:props.stage===4?props.detail.ownerUserId:null,result:'pending',summary:'',issues:'',changes:'',retestResult:'',sampleRoundId:null,moldSetId:null,checklist:{structure:false,assembly:false,appearance:false},details:{phoneModelIds:[],sourceRoundId:null,actualResult:'',blockingIssues:false,reviewerUserId:null,testContent:'',producedQty:null,inspectedQty:null,defectQty:null,inspectionMethod:setup.fullInspection?'全检':'',defectSummary:'',tests:copy((setup.tests||[]).map((t:Form)=>({...t,actual:'',measuredValue:null,method:'',sampleQty:null,tester:'',date:null,result:'pending'})))}});
 if(previous){form.previousReviewId=prior.id;form.details.previousRoundId=prior.roundId;form.title=`${prior.title||gateName.value} · 复评`;form.sampleRoundId=prior.sampleRoundId||null;form.moldSetId=prior.moldSetId||null;form.details.sourceRoundId=prior.details?.sourceRoundId||null;}
 const designId=props.detail.reviewGates?.find(r=>r.gate==='design'&&r.status==='passed')?.recordId;
 const design=((state.value.reviewRecords as Form[])||[]).find(r=>r.id===designId);
 const base=props.stage===4?((state.value.stage2Basis as Form)?.references||state.value.requirement?.references||[]):(design?.references||state.value.requirement?.references||[]);
 keys.value=(previous?.references||base).map(materialKey);form.materialBasis=prior.materialBasis||design?.materialBasis||(state.value.plan as Form)?.preliminaryMaterials||'';
 let sourceId=prior.details?.sourceRoundId||prior.sampleRoundId||(props.stage===4?state.value.prototypeRoundId:null);
 if(!sourceId&&gate.value!=='design'){
  const preferred=gate.value==='pilot'?props.detail.reviewGates?.find(r=>r.gate==='reliability'&&r.status==='passed')?.recordId:(state.value.validationSampleId||state.value.selectedRoundId);
  sourceId=sources.value.find(r=>r.roundId===preferred)?.roundId||(sources.value.length===1?sources.value[0]?.roundId:null);
 }
 if(sourceId){if(isRound.value)form.details.sourceRoundId=sourceId;else form.sampleRoundId=sourceId;sourceChanged(sourceId);}
 if(gate.value==='t0'&&!form.moldSetId&&molds.value.length===1)form.moldSetId=molds.value[0]?.moldSetId;
 editing.value=true;changed.value=false;error.value='';
}
function edit(row:Form){assign(form,row);form.details={phoneModelIds:[],...(row.details||{})};form.checklist={structure:false,assembly:false,manufacturability:false,...row.checklist};keys.value=(row.references||[]).map(materialKey);editing.value=true;changed.value=false;error.value='';}
async function run(fn:()=>Promise<void>){busy.value=true;error.value='';try{await fn();}catch(e){if(e!=='cancel'&&e!=='close'){error.value=requestError(e);ElMessage.error(error.value);}}finally{busy.value=false;}}
async function saveSetup(){await run(async()=>{const d=await researchAction(props.detail.productId,'review_setup',state.value.version,props.stage===7?{plannedQty:setup.plannedQty,minYieldRate:setup.minYieldRate,fullInspection:setup.fullInspection,reason:setup.reason}:{tests:setup.tests,reason:setup.reason});emit('updated',d);assign(setup,d.workflow.reviewSetup as Form);changed.value=false;ElMessage.success('验证要求已保存，实际结果仍需逐项记录');});}
async function save(confirm:boolean){await run(async()=>{
 if(confirm)await ElMessageBox.confirm('确认后保留本次实际结果和采用版本；后续更正通过复评或作废办理。','确认评审');
 let detail:ProductResearchDetail;
 if(isRound.value){
  await saveDevelopmentRound(props.detail.productId,form.roundId||null,{kind:gate.value==='pilot'?'pilot':'test',recordDate:form.recordDate||null,title:form.title,result:form.result||'pending',issues:form.issues||'',changes:form.changes||'',retestResult:form.retestResult||'',references:materialReferences(keys.value),confirm,version:state.value.version,requestId:crypto.randomUUID(),details:form.details});
  detail=await getProductResearchDetail(props.detail.productId);
 }else detail=await researchAction(props.detail.productId,'review_save',state.value.version,{...form,gate:gate.value,confirm,references:materialReferences(keys.value)});
 emit('updated',detail);changed.value=false;editing.value=false;ElMessage.success(confirm?'评审已确认，已保存实际依据':'评审草稿已保存');
 });}
async function remove(row:Form){await run(async()=>{await ElMessageBox.confirm('删除这条未确认的评审草稿？','删除草稿');let d:ProductResearchDetail;if(isRound.value){await deleteDevelopmentDraft(props.detail.productId,row.roundId,state.value.version);d=await getProductResearchDetail(props.detail.productId);}else d=await researchAction(props.detail.productId,'review_delete',state.value.version,{id:row.id});emit('updated',d);editing.value=false;changed.value=false;});}
async function voidRecord(row:Form){await run(async()=>{const response=await ElMessageBox.prompt('填写作废原因。历史依据继续保留，引用它的后续评审需要重新核对。','作废已确认记录',{inputValidator:v=>!!v?.trim()||'请填写作废原因'});const d=await researchAction(props.detail.productId,isRound.value?'round_void':'review_void',state.value.version,{id:row.id,roundId:row.roundId,reason:response.value});emit('updated',d);});}
async function closeEditor(){if(changed.value){try{await ElMessageBox.confirm('当前输入未保存，关闭后放弃本次修改？','未保存输入');}catch{return;}}editing.value=false;changed.value=false;}
function addTest(){setup.tests.push({code:crypto.randomUUID(),name:'',requirement:'',minValue:null,unit:''});changed.value=true;}
function inspect(row:Form){history.value=row;historyOpen.value=true;}
async function leave(){if(busy.value)return false;if(changed.value){try{await ElMessageBox.confirm('评审输入尚未保存，离开页面？','未保存输入');}catch{return false;}}return true;}
onBeforeRouteLeave(leave);onBeforeRouteUpdate(leave);
async function openGap(value:string,target?:string,moldId?:number){
 if(!gates.value.some(g=>g.key===value))return;
 const differentMold=value==='t0'&&moldId&&Number(form.moldSetId)!==moldId;
 if(gate.value!==value||differentMold){if(!(await leave()))return;gate.value=value;editing.value=false;changed.value=false;}
 if(value!=='setup'&&canEdit.value&&!editing.value){
  const source=state.value.validationSampleId||state.value.selectedRoundId;
  const candidates=records.value.filter(row=>!row.voidReason&&!row.voided&&(value!=='t0'||(!moldId||Number(row.moldSetId)===moldId)));
  const draft=candidates.find(row=>row.status==='draft'&&(value!=='t0'||!source||!row.sampleRoundId||Number(row.sampleRoundId)===Number(source)));
  if(draft)edit(draft);else newRecord(candidates.find(row=>row.status==='confirmed'&&(value!=='t0'||!source||Number(row.sampleRoundId)===Number(source))));
  if(value==='t0'&&moldId)form.moldSetId=moldId;
 }
 await nextTick();
 const element=panel.value?.querySelector<HTMLElement>(target==='material-basis'?'[data-readiness-target="material-basis"]':value==='setup'?'.toolbar':'.review-editor')||panel.value;
 element?.scrollIntoView({block:'center',behavior:'smooth'});
 element?.querySelector<HTMLElement>('textarea,input:not([type="hidden"]),button')?.focus({preventScroll:true});
}
defineExpose({openGap,leave,selectGate:(value:string)=>{if(!changed.value&&!editing.value&&gates.value.some(g=>g.key===value))gate.value=value;}});
const resultLabel=(r:string)=>({pending:'待评审',passed:'通过',failed:'未通过',rework:'需修模'} as Record<string,string>)[r]||r;
</script>

<template>
 <section ref="panel" class="review-panel" aria-label="本步骤确认与验证">
  <div class="section-heading"><h3 class="review-heading">{{stage===4?'样品确认记录':stage===6?'试模与验证结果':'试产验证与结果'}}</h3></div>
  <div class="review-nav"><el-radio-group v-model="gate" :disabled="editing||changed"><el-radio-button v-for="g in gates" :key="g.key" :value="g.key">{{g.label}}</el-radio-button></el-radio-group></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <div :key="gate"><template v-if="gate==='setup'">
   <div class="toolbar"><strong>{{stage===7?'试产判定要求':'样品测试判定要求'}}</strong><span class="muted">只填写要求，不代表已经测试通过。</span><el-button v-if="canSetup&&stage===6" @click="addTest">新增测试要求</el-button><el-button v-if="canSetup" type="primary" :loading="busy" @click="saveSetup">保存验证要求</el-button></div>
   <el-form label-position="top" :disabled="!canSetup||busy" @input="changed=true" @change="changed=true">
    <template v-if="stage===6"><section v-for="(test,index) in setup.tests" :key="test.code" class="test-requirement" :aria-label="`测试要求 ${index+1}`">
      <div class="test-item-heading"><h4>测试项目 {{index+1}}</h4><el-button v-if="canSetup&&stage===6" type="danger" link @click="setup.tests.splice(index,1);changed=true">删除</el-button></div>
      <el-form-item label="测试名称"><el-input v-model="test.name" :disabled="actualStage===7" aria-label="测试名称" placeholder="测试名称"/></el-form-item>
      <el-form-item label="测试要求"><el-input v-model="test.requirement" :disabled="actualStage===7" type="textarea" autosize aria-label="测试要求" placeholder="明确条件、次数及合格要求"/></el-form-item>
      <el-form-item label="最低实测值"><el-input-number v-model="test.minValue" :disabled="actualStage===7" :controls="false" :min="0" aria-label="最低实测值" placeholder="最低实测值"/></el-form-item>
      <el-form-item label="测量单位"><el-input v-model="test.unit" :disabled="actualStage===7" aria-label="测量单位" placeholder="单位"/></el-form-item>
    </section>
    <el-empty v-if="!setup.tests.length" description="尚未填写测试要求" :image-size="50"/>
    </template>
    <div v-if="stage===7" class="pilot-requirements">
      <el-form-item label="试产计划数量"><el-input-number v-model="setup.plannedQty" :min="1" :precision="0" :controls="false" placeholder="待定"/></el-form-item>
      <el-form-item label="最低良率 %"><el-input-number v-model="setup.minYieldRate" :min="0" :max="100" :controls="false" placeholder="待定，不能自动判通过"/></el-form-item>
      <el-form-item label="试产检验要求"><el-select v-model="setup.fullInspection" placeholder="检验要求待定" aria-label="试产检验要求"><el-option :value="true" label="要求全检"/><el-option :value="false" label="按确认的抽检方案"/></el-select></el-form-item>
    </div>
    <el-form-item label="验证要求修改原因"><el-input v-model="setup.reason" placeholder="已立项后调整要求时填写原因" aria-label="验证要求修改原因"/></el-form-item>
   </el-form>
   <p class="muted">{{stage===4?'在试产前确认计划数量、良率标准及检验要求，再记录实际产量、不良和处置结果。':'本阶段明确测试项目和判定标准，再填写实际测试结果；改模后按当前样品与图纸复验。'}}</p>
  </template>
  <template v-else>
   <div class="toolbar"><strong>{{gateName}}</strong><span class="muted">{{records.length}} 条记录</span><el-button v-if="canEdit&&!editing" type="primary" @click="newRecord()">新增{{gate==='pilot'?'试产':gate==='reliability'?'测试':'评审'}}</el-button><span v-if="!canEdit" class="muted">在{{targetStage===4?'样品确认':targetStage===6?'开模':'试产'}}阶段办理，当前可查看历史。</span></div>
   <div class="review-history"><article v-for="r in records" :key="r.roundId||r.id" class="review-row"><div><strong>{{r.title||gateName}}</strong><span>{{r.reviewDate||r.recordDate||'日期待补'}} · {{r.reviewerName||r.details?.reviewerName||'评审人待补'}}</span></div><span>{{r.voidReason||r.voided?'已作废':r.status==='draft'?'草稿':resultLabel(r.result)}}</span><div class="row-actions"><el-button link @click="inspect(r)">查看</el-button><template v-if="canEdit"><el-button v-if="r.status==='draft'" link @click="edit(r)">编辑</el-button><el-button v-if="r.status==='draft'" link type="danger" @click="remove(r)">删除草稿</el-button><el-button v-if="r.status==='confirmed'" link @click="newRecord(r)">新增复评</el-button></template><el-button v-if="canEdit&&r.status==='confirmed'&&!r.voidReason&&!r.voided" link type="danger" @click="voidRecord(r)">作废</el-button></div></article></div>
   <el-empty v-if="!records.length&&!editing" description="尚无评审记录；不能据此判定通过" :image-size="55"/>
   <el-form v-if="editing" class="review-editor" label-position="top" :disabled="busy" @input="changed=true" @change="changed=true">
    <div class="form-grid"><el-form-item label="记录名称"><el-input v-model="form.title"/></el-form-item><el-form-item label="实际评审日期"><el-date-picker v-if="isRound" v-model="form.recordDate" value-format="YYYY-MM-DD"/><el-date-picker v-else v-model="form.reviewDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="实际评审人"><el-select v-if="isRound" v-model="form.details.reviewerUserId" filterable clearable><el-option v-for="u in owners" :key="u.userId" :value="u.userId" :label="u.name"/></el-select><el-select v-else v-model="form.reviewerUserId" filterable clearable><el-option v-for="u in owners" :key="u.userId" :value="u.userId" :label="u.name"/></el-select></el-form-item>
    <el-form-item v-if="gate!=='design'||sources.length" :label="gate==='pilot'?'来源可靠性评审':gate==='design'?'采用样品（设计复评可选）':'采用样品'"><el-select v-if="isRound" v-model="form.details.sourceRoundId" filterable @change="sourceChanged"><el-option v-for="s in sources" :key="s.roundId" :value="s.roundId" :label="`${s.title} · 第${s.roundNo}轮`"/></el-select><el-select v-else v-model="form.sampleRoundId" filterable @change="sourceChanged"><el-option v-for="s in sources" :key="s.roundId" :value="s.roundId" :label="`${s.title} · 第${s.roundNo}轮`"/></el-select></el-form-item>
    <el-form-item v-if="gate==='t0'" label="实际模具套"><el-select v-model="form.moldSetId" filterable><el-option v-for="m in molds" :key="m.moldSetId" :value="m.moldSetId" :label="m.assetNumber||m.setName||`套 ${m.moldSetId}`"/></el-select></el-form-item>
    <el-form-item label="结论"><el-select v-model="form.result"><el-option label="待评审" value="pending"/><el-option label="通过" value="passed"/><el-option label="未通过" value="failed"/><el-option v-if="!isRound" label="需修模／整改" value="rework"/></el-select></el-form-item></div>
    <template v-if="!isRound"><el-form-item v-if="gate==='design'" label="采用的材料方案" data-readiness-target="material-basis"><el-input v-model="form.materialBasis" type="textarea" autosize placeholder="例如 PC 背板、TPU 包边、磁铁和支架方案；供应商及价格未知可留空"/></el-form-item><div class="checklist"><el-form-item label="结构核对"><el-checkbox v-model="form.checklist.structure">{{gate==='design'?'结构设计已核对':'样品结构实测已核对'}}</el-checkbox></el-form-item><el-form-item label="装配与功能核对"><el-checkbox v-model="form.checklist.assembly">{{gate==='design'?'装配与功能方案可行':'样品装配与功能实测已核对'}}</el-checkbox></el-form-item><el-form-item label="外观核对"><el-checkbox v-model="form.checklist.appearance">外观效果已核对</el-checkbox></el-form-item><el-form-item label="阻断问题"><el-checkbox v-model="form.blockingIssues">仍有阻断问题</el-checkbox></el-form-item></div><el-form-item label="实际评审说明"><el-input v-model="form.summary" type="textarea" autosize/></el-form-item></template>
    <template v-if="gate==='reliability'"><el-form-item label="本次测试内容"><el-input v-model="form.details.testContent" type="textarea" autosize/></el-form-item>
      <article v-for="t in form.details.tests" :key="t.code" class="test-result"><strong>{{t.name}} · {{t.requirement}}</strong><div class="test-grid">
        <el-form-item label="实际结果"><el-input v-model="t.actual" placeholder="实际结果" aria-label="测试实际结果"/></el-form-item>
        <el-form-item v-if="t.minValue!=null" :label="`实测值${t.unit?' / '+t.unit:''}`"><el-input-number v-model="t.measuredValue" :controls="false" :placeholder="`实测 ${t.unit||''}`" aria-label="测试实测值"/></el-form-item>
        <el-form-item label="测试方法"><el-input v-model="t.method" placeholder="测试方法" aria-label="测试方法"/></el-form-item>
        <el-form-item label="测试样本数"><el-input-number v-model="t.sampleQty" :controls="false" :min="1" :precision="0" placeholder="样本数" aria-label="测试样本数"/></el-form-item>
        <el-form-item label="测试人"><el-input v-model="t.tester" placeholder="测试人" aria-label="测试人"/></el-form-item>
        <el-form-item label="测试日期"><el-date-picker v-model="t.date" value-format="YYYY-MM-DD" placeholder="测试日期" aria-label="测试日期"/></el-form-item>
        <el-form-item label="单项测试结论"><el-select v-model="t.result" aria-label="单项测试结论"><el-option label="待验证" value="pending"/><el-option label="通过" value="passed"/><el-option label="未通过" value="failed"/></el-select></el-form-item>
      </div></article>
    </template>
    <template v-if="gate==='pilot'"><p>计划 {{setup.plannedQty??'待定'}} 件 · 最低良率 {{setup.minYieldRate??'待定'}}% · {{setup.fullInspection?'要求全检':'按确认的检验方式执行'}}</p><div class="form-grid"><el-form-item label="实际产量"><el-input-number v-model="form.details.producedQty" :controls="false" :precision="0" :min="0"/></el-form-item><el-form-item label="检验数量"><el-input-number v-model="form.details.inspectedQty" :controls="false" :precision="0" :min="0"/></el-form-item><el-form-item label="不良数量"><el-input-number v-model="form.details.defectQty" :controls="false" :precision="0" :min="0"/></el-form-item><el-form-item label="检验方式"><el-input v-model="form.details.inspectionMethod"/></el-form-item><el-form-item label="批次号"><el-input v-model="form.details.batchNo"/></el-form-item></div><el-form-item label="不良分类及处理说明（无不良也需说明）"><el-input v-model="form.details.defectSummary" type="textarea" autosize/></el-form-item></template>
    <template v-if="isRound"><el-form-item label="实际验证／试产总结"><el-input v-model="form.details.actualResult" type="textarea" autosize/></el-form-item><el-form-item label="后续阻断问题"><el-checkbox v-model="form.details.blockingIssues">仍有影响后续的阻断问题</el-checkbox></el-form-item></template>
    <el-form-item label="问题及整改要求"><el-input v-model="form.issues" type="textarea" autosize/></el-form-item><el-form-item label="本次采用的资料版本"><el-select v-model="keys" multiple filterable aria-label="评审采用版本"><el-option v-for="m in materials" :key="materialKey(m)" :value="materialKey(m)" :label="`${m.name} · ${m.versionNo||'版本待补'}`"/></el-select></el-form-item>
    <div class="toolbar"><el-button @click="save(false)">保存评审草稿</el-button><el-button type="primary" @click="save(true)">确认本次评审</el-button><el-button @click="closeEditor">关闭</el-button></div>
   </el-form>
  </template>
  </div><el-drawer v-model="historyOpen" title="评审历史及当时采用依据" size="760px"><ResearchSnapshot :value="history" @view="emit('view',$event)"/></el-drawer>
 </section>
</template>

<style scoped>
.review-panel{font-size:12px;min-width:0;container-type:inline-size;padding:18px 0}
.review-heading{font-size:14px;font-weight:700;line-height:1.5;margin:0}
.review-nav,.toolbar,.row-actions,.test-item-heading{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.review-nav,.toolbar{margin-bottom:8px}
.muted{color:var(--el-text-color-secondary)}
.form-grid,.test-grid,.checklist,.pilot-requirements,.test-requirement{display:grid;grid-template-columns:minmax(0,1fr);gap:12px;min-width:0}
.test-requirement{margin:12px 0;padding-bottom:12px;border-bottom:1px solid var(--el-border-color-lighter)}
.test-item-heading{justify-content:space-between}
.test-item-heading h4{font-size:13px;margin:0;font-weight:700}
.pilot-requirements{margin-top:12px}
.review-row{display:grid;grid-template-columns:minmax(150px,1fr) 90px minmax(180px,auto);gap:8px;padding:8px;border-bottom:1px solid var(--el-border-color-lighter);align-items:center}
.review-row>div:first-child{display:flex;flex-direction:column;gap:4px}
.review-row span{overflow-wrap:anywhere}
.review-editor{margin-top:16px;padding:16px 0 0;border-top:1px solid var(--el-border-color-lighter)}
.review-panel :deep(.el-select),.review-panel :deep(.el-date-editor),.review-panel :deep(.el-input-number){width:100%;max-width:100%}
.test-result{padding:12px 0;border-bottom:1px solid var(--el-border-color-lighter)}
.test-grid{margin-top:12px}
.review-panel :deep(.el-radio-button__inner){font-size:12px;padding:7px 10px}
.review-panel :deep(.el-button){font-size:12px;min-height:28px}
@container(max-width:850px){.review-row{grid-template-columns:minmax(0,1fr) 80px}.review-row>.row-actions{grid-column:1/-1}}
</style>
