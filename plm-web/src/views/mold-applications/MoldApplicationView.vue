<script setup lang="ts">
import MoldHistoryReconciliation from '@/views/mold-applications/components/MoldHistoryReconciliation.vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter, useRoute } from 'vue-router'
import {useCollaborationWorkspace} from '@/stores/collaborationWorkspace'
import { useUserStore } from '@/stores/user'
import { useWindowSize } from '@vueuse/core'
import * as api from '@/api/modules/moldApplications'
import SharedCompatiblePhoneSelector from '@/views/mold/components/SharedCompatiblePhoneSelector.vue'
import type { CompatiblePhone } from '@/types/molds'
import { phoneMemberName } from '@/utils/phoneMembers'
import {moldHandlerName} from '@/utils/moldApplicationHandlers'
import './application-drawer.scss'

const router=useRouter(),route=useRoute(),user=useUserStore(),workspace=useCollaborationWorkspace()
const {width:viewportWidth}=useWindowSize()
let loadEpoch=0,detailEpoch=0
const historyReview=ref(false)
const options=ref<api.MoldOptions>(),rows=ref<api.MoldApplication[]>([]),summary=ref<api.MoldSummary>(),total=ref(0)
const deletingId=ref<number|null>(null)
const loading=ref(false),busy=ref(false),error=ref(''),enabled=ref(true),detail=ref<api.MoldApplication>(),drawer=ref(false)
const query=reactive<api.MoldQuery>({page:1,size:50,status:'',projectId:Number(route.query.projectId)||undefined,unlinked:route.query.unlinked==='1'||undefined}),dateRange=ref<string[]>([]),filtersOpen=ref(false)
const statusNames:Record<string,string>={draft:'草稿',submitting:'提交中',submission_unknown:'提交待核对',submitted:'审批中',withdrawing:'撤回中',withdrawal_unknown:'撤回待核对',terminated:'已终止',completed:'流程已结束',converted:'已转项目',RUNNING:'运行中',COMPLETED:'流程已结束',TERMINATED:'已终止'}
const stages:{value:api.MoldBusinessStatus;label:string}[]=['draft','awaiting_approval','approved','rejected','withdrawn','terminated'].map(value=>({value:value as api.MoldBusinessStatus,label:api.moldBusinessStatusNames[value as api.MoldBusinessStatus]}))
function stage(r:api.MoldApplication):api.MoldBusinessStatus{return r.businessStatus||'approval_unknown'}
const hasHistoricalAssociation=(r:api.MoldApplication)=>!!r.historicalAssociation?.associationOnly
const label=(r:api.MoldApplication)=>api.moldBusinessStatusNames[stage(r)]
const stageType=(r:api.MoldApplication)=>hasHistoricalAssociation(r)?'info':stage(r)==='approved'?'success':['rejected','terminated'].includes(stage(r))?'danger':'info'
function chinaTime(value:string|null|undefined,dateOnly=false){if(!value)return '';const d=new Date(value);if(Number.isNaN(d.getTime()))return value;return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',...(dateOnly?{}:{hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false})}).format(d)}
const totalCount=computed(()=>summary.value?.counts.reduce((n,r)=>n+Number(r.count),0)||0)
const count=(state:api.MoldBusinessStatus)=>summary.value?.businessCounts?.find(r=>r.businessStatus===state)?.count??0
const activeFilters=computed(()=>Number(!!query.tipo)+Number(!!query.originator)+Number(!!dateRange.value.length))
function selectStage(value:string){query.status=value;filter()}
function resetFilters(){query.keyword='';query.tipo='';query.originator='';dateRange.value=[];filter()}
const moldCodes=(r:api.MoldApplication)=>r.generatedCodes?.split(/\s*\/\s*/).filter(Boolean)||[]
const currentPhoneName=(row:api.MoldApplication)=>row.standardPhone?phoneMemberName(row.standardPhone):row.model||'未填写型号'
const currentPhoneCode=(row:api.MoldApplication)=>row.standardPhone?row.standardPhone.phoneModelCode||'待分配':row.phoneModelCode||'待核对'
function sourceRecords(row?:api.MoldApplication):Record<string,unknown>[] {const sources=row?.sourceSnapshot?.sources;return Array.isArray(sources)?sources as Record<string,unknown>[]:[]}
function sourceUrl(source:Record<string,unknown>){const raw=source.source_url||source.sourceUrl;try{const value=new URL(String(raw||''));return ['http:','https:'].includes(value.protocol)?value.href:undefined}catch{return undefined}}
const controls=computed(()=>options.value?.controls.filter(c=>!['DDPhotoField','DDAttachment'].includes(c.type)&&!['generatedCodes','informationComplete'].includes(c.key))||[])
const tipoOptions=computed(()=>options.value?.controls.find(c=>c.key==='tipo')?.options||[])
const photos=ref<Record<number,string>>({})
function clearPhotos(){Object.values(photos.value).forEach(URL.revokeObjectURL);photos.value={}}
async function load(){const auth=user.token,epoch=++loadEpoch;loading.value=true;error.value='';try{const [page,stats]=await Promise.all([api.getMoldApplications({...query,from:dateRange.value[0],until:dateRange.value[1]}),api.getMoldSummary({projectId:query.projectId,unlinked:query.unlinked})]);if(auth!==user.token||epoch!==loadEpoch)return;rows.value=page.content;total.value=page.totalElements;summary.value=stats}catch(e){if(auth===user.token&&epoch===loadEpoch)error.value=e instanceof Error?e.message:'读取失败，请重试'}finally{if(auth===user.token&&epoch===loadEpoch)loading.value=false}}
function filter(){query.page=1;void load()}
async function start(){const auth=user.token;try{const config=await api.getMoldApplicationConfig();if(auth!==user.token)return;enabled.value=config.enabled;if(!enabled.value)return;const next=await api.getMoldOptions();if(auth!==user.token)return;options.value=next;await load();await openRoute()}catch{if(auth===user.token)error.value='无法读取开模申请，请检查登录权限并重试'}}
async function open(id:number){const auth=user.token,epoch=++detailEpoch;busy.value=true;clearPhotos();try{const next=await api.getMoldApplication(id);if(auth!==user.token||epoch!==detailEpoch)return;detail.value=next;drawer.value=true;await images()}finally{if(auth===user.token&&epoch===detailEpoch)busy.value=false}}
async function attempt(action:()=>Promise<unknown>){try{await action()}catch(e){if(e==='cancel'||e==='close')return;error.value=e instanceof Error?e.message:'操作失败，请核对后重试';ElMessage.error(error.value)}}
function openRow(row:api.MoldApplication){void attempt(()=>open(row.applicationId))}
async function images(){if(!detail.value)return;clearPhotos();const id=detail.value.applicationId,auth=user.token;await Promise.all((detail.value.files||[]).filter(f=>f.contentType?.startsWith('image/')&&f.downloadStatus==='downloaded').map(async f=>{try{const blob=await api.downloadMoldFile(id,f.attachmentId);if(detail.value?.applicationId===id&&user.token===auth)photos.value[f.attachmentId]=URL.createObjectURL(blob)}catch{}}))}
async function file(f:api.MoldFile){if(!detail.value)return;const blob=await api.downloadMoldFile(detail.value.applicationId,f.attachmentId);const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=f.fileName;a.click();URL.revokeObjectURL(url)}
function edit(row?:api.MoldApplication){void router.push({path:'/market-watch',query:row?{applicationId:row.applicationId,edit:'1'}:{new:'1'}})}
async function openRoute(){openConfigurationRoute();query.projectId=Number(route.query.projectId)||undefined;query.unlinked=route.query.unlinked==='1'||undefined;const auth=user.token,id=Number(route.query.applicationId);if(route.query.edit==='1'||route.query.phoneModelId){await router.replace({path:'/market-watch',query:{...route.query}});return}if(Number.isSafeInteger(id)&&id>0){const row=await api.getMoldApplication(id);if(auth!==user.token)return;detail.value=row;drawer.value=true;await images();if(auth===user.token&&Number(route.query.applicationId)===id&&route.query.review==='historical'&&row.sourceSystem==='dingtalk'&&options.value?.canConvert)await openHistoricalAssociation()}}
watch(()=>[route.query.projectId,route.query.unlinked],()=>{query.projectId=Number(route.query.projectId)||undefined;query.unlinked=route.query.unlinked==='1'||undefined;if(options.value){query.page=1;void load()}})
watch(()=>route.fullPath,()=>{if(options.value)void attempt(openRoute)})
async function withdraw(){if(!detail.value)return;await ElMessageBox.confirm('撤回钉钉申请，确认成功后复制为新的 PLM 草稿。原流程记录会保留。','撤回并修订',{confirmButtonText:'撤回并复制',cancelButtonText:'取消'});busy.value=true;try{const draft=await api.withdrawMoldApplication(detail.value.applicationId,detail.value.version);workspace.changed();drawer.value=false;edit(draft);await load()}finally{busy.value=false}}
async function removeDraft(row:api.MoldApplication){
  if(!row.canDeleteDraft||deletingId.value!==null)return;
  const auth=user.token;deletingId.value=row.applicationId;
  try{
    await ElMessageBox.confirm(`确定删除“${currentPhoneName(row)}”的开模申请草稿？删除后将从列表移除，手机型号及已有模具资料保留。`,'删除草稿',{type:'warning',confirmButtonText:'确认删除',cancelButtonText:'保留草稿',confirmButtonClass:'el-button--danger'});
    if(auth!==user.token)return;
    await api.deleteMoldDraft(row.applicationId,row.version);if(auth!==user.token)return;
    if(detail.value?.applicationId===row.applicationId){++detailEpoch;clearPhotos();drawer.value=false;detail.value=undefined;}
    rows.value=rows.value.filter(r=>r.applicationId!==row.applicationId);workspace.changed();ElMessage.success('草稿已删除');
    if(Number(route.query.applicationId)===row.applicationId){const query={...route.query};delete query.applicationId;delete query.review;await router.replace({path:route.path,query});}
    query.page=1;await load();
  }catch(e){if(auth===user.token&&e!=='cancel'&&e!=='close'){error.value=e instanceof Error?e.message:'删除失败，请刷新后重试';ElMessage.error(error.value);}}
  finally{if(auth===user.token)deletingId.value=null;}
}
async function syncOne(){if(!detail.value)return;busy.value=true;try{detail.value=await api.syncMoldApplication(detail.value.applicationId);workspace.changed();await images();await load()}finally{busy.value=false}}
async function syncAll(){busy.value=true;try{const result=await api.syncMoldApplications('2026-01-01',new Date().toLocaleDateString('sv-SE',{timeZone:'Asia/Shanghai'}));ElMessage.success(`已同步 ${result.synced} 条，失败 ${result.failedInstanceIds.length} 条`);workspace.changed();options.value=await api.getMoldOptions();await load()}finally{busy.value=false}}
const configOpen=ref(false),configTipo=ref(''),configLine=ref<number>(),configPrefix=ref(''),configMaterials=reactive({tpu:'10',pc:'20'}),configVersion=ref<number>()
function chooseTipo(tipo:string){const c=options.value?.configs.find(c=>c.tipo===tipo);configLine.value=c?.productLineId;configPrefix.value=c?.productSpecificCode||'';configVersion.value=c?.version;configMaterials.tpu=c?.materials.find(m=>m.partType==='TPU')?.code||'10';configMaterials.pc=c?.materials.find(m=>m.partType==='PC')?.code||'20'}
function chooseLine(line:number){configPrefix.value=options.value?.productLines.find(p=>p.productId===line)?.productSpecificCode||''}
function openConfigurationRoute(){if(route.query.configure!=='1')return;if(!options.value?.administrator){error.value='产品线配置仅管理员可维护，请联系管理员补充当前审批 Tipo 与产品线映射。';return}const line=Number(route.query.productLineId);if(!options.value.productLines.some(p=>p.productId===line)){error.value='产品不存在或无查看权限，请重新选择产品线。';return}const config=options.value.configs.find(c=>c.productLineId===line&&tipoOptions.value.includes(c.tipo));configTipo.value=config?.tipo||'';chooseTipo(configTipo.value);configLine.value=line;chooseLine(line);configOpen.value=true}
async function saveConfig(){if(!configLine.value)return;busy.value=true;try{await api.saveMoldConfig({tipo:configTipo.value,productLineId:configLine.value,productSpecificCode:configPrefix.value,materials:[...(configMaterials.tpu?[{code:configMaterials.tpu,partType:'TPU'}]:[]),...(configMaterials.pc?[{code:configMaterials.pc,partType:'PC'}]:[])],...(configVersion.value?{version:configVersion.value}:{})});options.value=await api.getMoldOptions();chooseTipo(configTipo.value);ElMessage.success('产品线配置已保存');configOpen.value=false}finally{busy.value=false}}
const historyOpen=ref(false),historyPreview=ref<api.MoldHistoricalPreview>(),historyBusy=ref(false),historyError=ref(''),historyPhoneIds=ref<number[]>([])
const historySelection=reactive({phoneModelId:null as number|null,productLineId:null as number|null,projectId:null as number|null,itemId:null as number|null,moldSetId:null as number|null,reason:'',confirmPhoneCorrection:false})
let historyEpoch=0
const historyProject=computed(()=>historyPreview.value?.projects.find(p=>p.projectId===Number(historySelection.projectId)))
const historySets=computed(()=>historyPreview.value?.sets.filter(s=>s.itemId===Number(historySelection.itemId))||[])
const historySet=computed(()=>historySets.value.find(s=>s.moldSetId===Number(historySelection.moldSetId)))
const correctingHistoricalPhone=computed(()=>!!detail.value?.phoneModelId&&Number(historySelection.phoneModelId)!==detail.value.phoneModelId)
const historyCanSave=computed(()=>!historyBusy.value&&!!historySelection.phoneModelId&&!!historySelection.productLineId&&!!historySelection.reason.trim()&&(!historySelection.projectId||!!historyProject.value&&!historyProject.value.blockedReason)&&(!historySelection.moldSetId||!!historySelection.itemId&&!!historySet.value&&!historySet.value.blockedReason)&&(!correctingHistoricalPhone.value||historySelection.confirmPhoneCorrection))
async function loadHistoricalPreview(initial=false){
  if(!detail.value)return;const id=detail.value.applicationId,auth=user.token,epoch=++historyEpoch;historyBusy.value=true;historyError.value=''
  try{const value=await api.previewMoldHistoricalAssociation(id,{phoneModelId:historySelection.phoneModelId,productLineId:historySelection.productLineId});if(auth!==user.token||epoch!==historyEpoch||detail.value?.applicationId!==id)return;historyPreview.value=value;historySelection.productLineId=value.selectedProductLineId;historySelection.phoneModelId=value.standardPhone?.phoneModelId||historySelection.phoneModelId;historyPhoneIds.value=historySelection.phoneModelId?[historySelection.phoneModelId]:[]
    if(initial){historySelection.projectId=value.projects.some(p=>p.projectId===detail.value?.projectId&&!p.blockedReason)?detail.value.projectId:null;historySelection.itemId=value.items.some(i=>i.itemId===detail.value?.itemId)?detail.value.itemId:null;historySelection.moldSetId=value.sets.some(s=>s.moldSetId===detail.value?.moldSetId&&!s.blockedReason)?detail.value.moldSetId:null}
  }catch(e){if(auth===user.token&&epoch===historyEpoch)historyError.value=e instanceof Error?e.message:'历史关联选项读取失败，请重试'}finally{if(auth===user.token&&epoch===historyEpoch)historyBusy.value=false}
}
async function openHistoricalAssociation(){if(!detail.value)return;Object.assign(historySelection,{phoneModelId:detail.value.phoneModelId||null,productLineId:detail.value.productLineId||null,projectId:null,itemId:null,moldSetId:null,reason:'',confirmPhoneCorrection:false});historyPreview.value=undefined;historyOpen.value=true;await loadHistoricalPreview(true)}
function changeHistoricalProduct(){Object.assign(historySelection,{projectId:null,itemId:null,moldSetId:null});void loadHistoricalPreview()}
function chooseHistoricalPhone(phones:CompatiblePhone[]){if(phones.length!==1){historyPhoneIds.value=historySelection.phoneModelId?[historySelection.phoneModelId]:[];ElMessage.warning('历史关联请选择一款明确的标准手机');return}historySelection.phoneModelId=phones[0]!.phoneModelId;historySelection.confirmPhoneCorrection=false;Object.assign(historySelection,{projectId:null,itemId:null,moldSetId:null});void loadHistoricalPreview()}
function changeHistoricalProject(){const item=historyProject.value?.linkedItemId;historySelection.itemId=item&&historyPreview.value?.items.some(i=>i.itemId===item)?item:null;historySelection.moldSetId=null}
async function saveHistoricalAssociation(){if(!detail.value||!historyPreview.value||!historyCanSave.value)return;const id=detail.value.applicationId,auth=user.token,epoch=historyEpoch;historyBusy.value=true;historyError.value=''
  try{const row=await api.associateMoldHistory(id,{version:historyPreview.value.application.version,phoneModelId:Number(historySelection.phoneModelId),productLineId:Number(historySelection.productLineId),projectId:historySelection.projectId==null?null:Number(historySelection.projectId),itemId:historySelection.itemId==null?null:Number(historySelection.itemId),moldSetId:historySelection.moldSetId==null?null:Number(historySelection.moldSetId),reason:historySelection.reason.trim(),confirmPhoneCorrection:historySelection.confirmPhoneCorrection});if(auth!==user.token||epoch!==historyEpoch||detail.value?.applicationId!==id)return;detail.value=row;historyOpen.value=false;workspace.changed();ElMessage.success('历史关系已关联，适配待确认');await load()
  }catch(e){if(auth===user.token&&epoch===historyEpoch)historyError.value=e instanceof Error?e.message:'保存失败，请核对原值与权限后重试'}finally{if(auth===user.token&&epoch===historyEpoch)historyBusy.value=false}
}
async function retryProvision(){if(!detail.value)return;detail.value=await api.retryMoldProvision(detail.value.applicationId,detail.value.version);workspace.changed();await load()}
async function resolveExecution(action:'resume'|'terminate'){if(!detail.value)return;const response=await ElMessageBox.prompt('请填写工程处理原因，原始审批与已发生进度将保留。',action==='resume'?'继续开模':'终止开模',{inputType:'textarea',inputValidator:value=>!!value?.trim()||'请填写原因'});detail.value=await api.resolveMoldExecution(detail.value.applicationId,{version:detail.value.version,action,reason:response.value});workspace.changed();await load()}
const targetCorrectionOpen=ref(false),correction=reactive({executionIntent:'new' as 'new'|'new_set',targetItemId:null as number|null,targetProjectId:null as number|null,reason:''}),correctionTargets=ref<api.MoldTargetOptions>({projects:[],items:[]})
async function openTargetCorrection(){if(!detail.value?.phoneModelId||!detail.value.productLineId)return;correctionTargets.value=await api.getMoldTargetOptions(detail.value.phoneModelId,detail.value.productLineId);Object.assign(correction,{executionIntent:detail.value.executionIntent==='new_set'?'new_set':'new',targetItemId:detail.value.targetItemId||null,targetProjectId:detail.value.targetProjectId||null,reason:''});targetCorrectionOpen.value=true}
async function saveTargetCorrection(){if(!detail.value)return;detail.value=await api.correctMoldTargets(detail.value.applicationId,{version:detail.value.version,...correction});workspace.changed();targetCorrectionOpen.value=false;await load()}
onMounted(start);onBeforeUnmount(()=>{++historyEpoch;clearPhotos()});watch(()=>user.token,()=>{++loadEpoch;++detailEpoch;deletingId.value=null;loading.value=false;busy.value=false;clearPhotos();rows.value=[];total.value=0;summary.value=undefined;detail.value=undefined;drawer.value=false;options.value=undefined;++historyEpoch;historyOpen.value=false;historyPreview.value=undefined;historyBusy.value=false;historyError.value='';historyPhoneIds.value=[];configOpen.value=false})
</script>

<template>
  <section class="mold-applications" v-loading="busy">
    <header class="page-head"><h2>开模申请进度</h2><div class="tools"><el-button v-if="options?.canConvert" size="small" @click="historyReview=true">历史补链与缺项</el-button><el-button size="small" @click="start">刷新</el-button><el-popover v-if="options?.administrator" trigger="click" placement="bottom-end" :width="150" :persistent="false"><template #reference><el-button size="small">更多</el-button></template><div class="admin-tools"><el-button text @click="configOpen=true">产品线配置</el-button><el-button text @click="attempt(syncAll)">同步钉钉</el-button></div></el-popover><el-button v-if="options?.canCreate" size="small" type="primary" @click="edit()">发起申请</el-button></div></header>
    <el-alert v-if="!enabled" title="开模申请暂未启用" type="info" :closable="false" />
    <template v-else>
      <div class="status-tabs" role="tablist" aria-label="申请状态"><button role="tab" :aria-selected="!query.status" @click="selectStage('')">全部 <span>{{ totalCount }}</span></button><button v-for="s in stages" :key="s.value" role="tab" :aria-selected="query.status===s.value" @click="selectStage(s.value)">{{ s.label }} <span>{{ count(s.value) }}</span></button></div>
      <div class="filters"><el-input v-model="query.keyword" size="small" aria-label="申请关键词" placeholder="型号 / 编码 / 审批编号" clearable @keyup.enter="filter"/><el-button size="small" @click="filter">查询</el-button><el-button size="small" :aria-expanded="filtersOpen" aria-controls="mold-advanced-filters" @click="filtersOpen=!filtersOpen">筛选{{ activeFilters?` · ${activeFilters}`:'' }}</el-button></div>
      <div v-if="filtersOpen" id="mold-advanced-filters" class="advanced-filters"><el-select v-model="query.tipo" size="small" aria-label="Tipo筛选" placeholder="全部 Tipo" clearable filterable @change="filter"><el-option v-for="t in tipoOptions" :key="t" :label="t" :value="t"/></el-select><el-select v-model="query.originator" size="small" aria-label="发起人筛选" placeholder="全部发起人" clearable @change="filter"><el-option v-for="p in options?.originators" :key="p.id" :label="p.name" :value="p.id"/></el-select><el-date-picker v-model="dateRange" size="small" type="daterange" value-format="YYYY-MM-DD" start-placeholder="发起日期" end-placeholder="截至日期" @change="filter"/><el-button size="small" text @click="resetFilters">清空筛选</el-button></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button text @click="start">重试</el-button></el-alert>
      <div class="application-list" role="table" aria-label="开模申请列表" v-loading="loading">
        <div role="rowgroup" class="list-head"><div role="row" class="application-row"><span role="columnheader">申请 / 型号</span><span role="columnheader">Tipo / 产品类型</span><span role="columnheader">状态</span><span role="columnheader">模具编码</span><span role="columnheader">发起人 / 日期</span><span role="columnheader">期望交付</span><span role="columnheader">项目／操作</span></div></div>
        <div role="rowgroup" class="list-body" tabindex="0" aria-label="申请记录，垂直滚动查看更多">
          <div v-for="row in rows" :key="row.applicationId" role="row" class="application-row application-record" @dblclick="openRow(row)">
            <div role="cell" class="record-identity"><el-button link type="primary" @click="openRow(row)">{{ currentPhoneName(row) }}</el-button><small v-if="row.standardPhone||row.phoneModelCode">{{row.standardPhone?'手机':'历史手机编码'}} {{currentPhoneCode(row)}}</small></div>
            <div role="cell" class="record-tipo">{{ row.tipo||'—' }}</div>
            <div role="cell" class="record-status"><el-tag size="small" :type="stageType(row)">{{ label(row) }}</el-tag><small v-if="hasHistoricalAssociation(row)">已补明确历史关系</small><small v-if="row.executionStatus==='paused'&&!hasHistoricalAssociation(row)">开模暂停</small><small v-else-if="row.executionStatus==='terminated'&&!hasHistoricalAssociation(row)">工程已终止</small><small v-if="row.provisioningStatus==='blocked'">建档待处理</small></div>
            <div role="cell" class="record-codes"><span v-for="code in moldCodes(row)" :key="code">{{ code }}</span><span v-if="!row.generatedCodes">—</span></div>
            <div role="cell" class="record-person"><span>{{ row.originatorName||'本人草稿' }}</span><small>{{ chinaTime(row.sourceCreatedAt||row.createdAt,true)||'—' }}</small></div>
            <div role="cell" class="record-date"><small class="compact-label">交期</small>{{ row.form.expectedDeliveryDate||'—' }}</div>
            <div role="cell" class="record-project"><el-button v-if="row.projectId" size="small" link @click="router.push({path:'/projects',query:{productId:row.projectId}})">打开项目</el-button><el-button v-else-if="row.canDeleteDraft" link size="small" type="danger" :loading="deletingId===row.applicationId" :disabled="deletingId!==null" @click.stop="removeDraft(row)">删除草稿</el-button><small v-else>未转入</small></div>
          </div><el-empty v-if="!rows.length&&!loading" description="没有符合条件的申请" :image-size="64"/>
        </div>
      </div><el-pagination size="small" v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[20,50,100]" :pager-count="5" layout="sizes, prev, pager, next" @current-change="load" @size-change="filter"/>
    </template>
    <el-drawer class="application-drawer" :style="{'--el-font-size-base':'12px'}" v-model="drawer" title="开模申请详情" size="min(980px,92vw)">
      <template v-if="detail"><h2>{{ currentPhoneName(detail) }} · {{ detail.tipo }}</h2><p>{{ detail.approvalNo||`申请 #${detail.applicationId}` }} · {{ label(detail) }} · {{ detail.originatorName||'PLM草稿' }}</p><el-alert v-if="detail.lastError" :title="detail.lastError" type="warning" :closable="false"/>
        <div class="tools"><el-button v-if="detail.canDeleteDraft" type="danger" plain :loading="deletingId===detail.applicationId" :disabled="deletingId!==null" @click="removeDraft(detail)">删除草稿</el-button><el-button v-if="detail.status==='draft'&&options?.canCreate" @click="edit(detail)">继续草稿</el-button><el-button v-if="options?.administrator&&detail.instanceId" @click="attempt(syncOne)">同步此流程</el-button><el-button v-if="options?.canSubmit&&detail.status==='submitted'&&!detail.projectId" @click="attempt(withdraw)">撤回并修订</el-button><el-button v-if="options?.canConvert&&detail.sourceSystem==='dingtalk'" type="primary" @click="attempt(openHistoricalAssociation)">{{hasHistoricalAssociation(detail)?'更正历史关联':'核对并关联已有资料'}}</el-button></div>
        <el-alert v-if="detail.historicalAssociation" title="历史关系已关联，适配待确认" type="info" :closable="false"><p>{{detail.historicalAssociation.reason}} · {{detail.historicalAssociation.actorName}} · {{chinaTime(detail.historicalAssociation.associatedAt)}}</p><p>本次只补已有资料关联；审批原文与刻码保留，不授予执行或共模适配。</p></el-alert>
        <el-alert v-if="detail.provisioningError" :title="detail.provisioningError" type="warning" :closable="false"><el-button v-if="options?.canConvert&&detail.approvalGranted" size="small" @click="attempt(retryProvision)">重试自动建档</el-button><el-button v-if="options?.canConvert&&detail.approvalGranted" size="small" @click="attempt(openTargetCorrection)">核对建档目标</el-button></el-alert>
        <p v-if="detail.authorization">周汉琴 / Tiffany 已同意 · {{ chinaTime(detail.authorization.approvedAt) || '同意时间待核对' }}</p>
        <el-alert v-if="detail.executionStatus==='paused'&&!hasHistoricalAssociation(detail)" title="后续审批拒绝、撤回或终止，开模已暂停；已发生记录保留" type="warning" :closable="false"><el-button v-if="options?.canConvert" size="small" @click="attempt(()=>resolveExecution('resume'))">工程确认继续</el-button><el-button v-if="options?.canConvert" size="small" @click="attempt(()=>resolveExecution('terminate'))">工程确认终止</el-button></el-alert>
        <p v-if="detail.executionResolution?.action==='resume'||detail.executionResolution?.action==='terminate'">工程处理：{{ detail.executionResolution.action==='resume'?'继续开模':'终止开模' }} · {{ detail.executionResolution.reason }} · {{ detail.executionResolution.actorName }}</p>
        <div v-if="detail.projectId" class="tools"><el-button @click="router.push({path:'/projects',query:{productId:detail.projectId}})">打开项目</el-button><el-button @click="router.push({path:'/market-watch',query:{itemId:detail.itemId,panel:'market-data'}})">市场资料维护</el-button><el-button @click="router.push({path:'/molds',query:{moldSetId:detail.moldSetId}})">模具台账</el-button></div>
        <el-descriptions :column="viewportWidth<760?1:2" border><el-descriptions-item :label="detail.standardPhone?'当前标准手机编码':'历史手机编码'">{{ currentPhoneCode(detail) }}</el-descriptions-item><el-descriptions-item v-if="detail.standardPhone" label="申请原型号">{{detail.model||'—'}}</el-descriptions-item><el-descriptions-item v-if="detail.originalPhoneModelCode" label="申请原手机编码">{{detail.originalPhoneModelCode}}</el-descriptions-item><el-descriptions-item label="开模方式">{{ detail.executionIntent==='new_set'?'已有主档加开一套':detail.executionIntent==='reuse'?'历史复用申请':'新建模具主档' }}</el-descriptions-item><el-descriptions-item label="来源系统">{{detail.sourceSystem}}</el-descriptions-item><el-descriptions-item label="钉钉流程状态">{{ statusNames[detail.remoteStatus||'']||detail.remoteStatus||'尚未提交钉钉' }}</el-descriptions-item><el-descriptions-item label="审批结果">{{ detail.remoteResult==='agree'?'同意':detail.remoteResult==='refuse'?'拒绝':detail.remoteResult||'—' }}</el-descriptions-item><el-descriptions-item label="申请内部阶段">{{ statusNames[detail.status]||detail.status }}</el-descriptions-item><el-descriptions-item v-for="c in controls" :key="c.key" :label="c.label">{{ detail.form[c.key]||'—' }}</el-descriptions-item><el-descriptions-item label="生成的编码">{{ detail.generatedCodes||'—' }}</el-descriptions-item><el-descriptions-item label="信息完成状态">{{ detail.form.informationComplete||'—' }}</el-descriptions-item><el-descriptions-item label="所在部门">{{ detail.departmentName||'—' }}</el-descriptions-item></el-descriptions>
        <el-collapse v-if="detail.sourceSnapshot"><el-collapse-item title="市场机型来源快照" name="sources"><p>采集快照 {{ chinaTime(String(detail.sourceSnapshot.capturedAt||'')) }}</p><div v-for="(source,index) in sourceRecords(detail)" :key="index" class="source-record"><span>{{ source.country||source.source_country||'市场待核对' }} · {{ source.source_name||'来源待核对' }} · {{ source.brand }} {{ source.model }}</span><span>{{ source.price_display||source.price_raw||'采集价格未提供' }}</span><a v-if="sourceUrl(source)" :href="sourceUrl(source)" target="_blank" rel="noopener noreferrer">查看来源</a></div></el-collapse-item></el-collapse>
        <h3>图片与附件</h3><div class="photo-grid"><div v-for="f in detail.files" :key="f.attachmentId"><el-image v-if="photos[f.attachmentId]" :src="photos[f.attachmentId]" :preview-src-list="Object.values(photos)" fit="contain"/><el-button text @click="file(f)">{{ f.category==='key_photo'?'关键图片':f.category==='sample_photo'?'测试图片':f.fileName }}</el-button><small v-if="f.downloadStatus!=='downloaded'">等待下载授权</small></div></div>
        <h3>审批时间线</h3><el-timeline><el-timeline-item v-for="activity in detail.activities?.filter(a=>a.type!=='task')" :key="activity.activityId" :timestamp="chinaTime(activity.occurredAt)"><span>{{ activity.payload.operation_type==='START_PROCESS_INSTANCE'?'发起审批':activity.payload.operation_type==='EXECUTE_TASK_NORMAL'?'处理审批':activity.payload.operation_type==='TERMINATE_PROCESS_INSTANCE'?'终止流程':activity.type }}</span><el-tag size="small" v-if="activity.payload.operation_result&&activity.payload.operation_result!=='NONE'">{{ activity.payload.operation_result==='AGREE'?'同意':activity.payload.operation_result }}</el-tag><p>{{ activity.actorName||activity.actorId }} {{ activity.payload.remark }}</p></el-timeline-item></el-timeline>
        <h3>当前任务</h3><template v-if="detail.currentHandlers?.length"><p v-for="(handler,index) in detail.currentHandlers" :key="handler.taskId||index" class="handler-evidence">{{moldHandlerName(handler)}} · 原账号 {{handler.userId||'未提供'}} · 任务 {{handler.taskId||'待核对'}}</p></template><template v-else><p v-for="task in detail.activities?.filter(a=>a.type==='task'&&a.payload.task_status==='RUNNING')" :key="task.activityId">处理人 {{ task.actorName||task.actorId }} · 任务 {{ task.taskId }}</p></template>
      </template>
    </el-drawer>
    <el-dialog v-model="configOpen" title="Tipo 与 PLM 产品线配置" width="620px"><el-form label-position="top"><el-form-item label="Tipo"><el-select v-model="configTipo" @change="chooseTipo"><el-option v-for="t in tipoOptions" :key="t" :value="t" :label="t"/></el-select></el-form-item><el-form-item label="产品线"><el-select v-model="configLine" filterable @change="chooseLine"><el-option v-for="p in options?.productLines" :key="p.productId" :value="p.productId" :label="`${p.productName} · ${p.productSpecificCode||'无编码'}`"/></el-select></el-form-item><el-form-item label="产品特定编码（来自产品线）"><el-input v-model="configPrefix" readonly/></el-form-item><el-form-item label="TPU 两位材质编码"><el-input v-model="configMaterials.tpu" maxlength="2"/></el-form-item><el-form-item label="PC 两位材质编码（没有则留空）"><el-input v-model="configMaterials.pc" maxlength="2"/></el-form-item></el-form><template #footer><el-button @click="attempt(saveConfig)">保存配置</el-button></template></el-dialog>
    <el-dialog v-model="targetCorrectionOpen" title="核对自动建档目标" width="min(620px,94vw)"><el-form label-position="top"><el-form-item label="开模方式"><el-radio-group v-model="correction.executionIntent"><el-radio value="new">新建模具主档</el-radio><el-radio value="new_set">已有主档加开一套</el-radio></el-radio-group></el-form-item><el-form-item v-if="correction.executionIntent==='new_set'" label="已有模具主档"><el-select v-model="correction.targetItemId" filterable><el-option v-for="item in correctionTargets.items" :key="item.itemId" :value="item.itemId" :label="`${item.moldName} · ${item.compatibleModels}`"/></el-select></el-form-item><el-form-item label="型号项目"><el-select v-model="correction.targetProjectId" clearable placeholder="无匹配项目则新建"><el-option v-for="project in correctionTargets.projects" :key="project.projectId" :value="project.projectId" :label="project.productName"/></el-select></el-form-item><el-form-item label="工程核对原因"><el-input v-model="correction.reason" type="textarea" :rows="3"/></el-form-item></el-form><template #footer><el-button type="primary" :disabled="!correction.reason.trim()" @click="attempt(saveTargetCorrection)">保存目标并重试</el-button></template></el-dialog>
    <el-dialog v-model="historyOpen" title="核对并关联已有资料" width="min(900px,94vw)" :style="{'--el-font-size-base':'12px'}" :close-on-click-modal="false" class="history-association-dialog">
      <el-alert title="仅关联已存在的项目、主档和实物套；不新建任何实物，也不确认适配。" type="info" :closable="false"/>
      <p v-if="detail">申请原文：{{detail.model||'未填写'}} · {{detail.tipo||'未填写'}} · 原刻码 {{detail.generatedCodes||'原编码缺失，仍可核对已有关系'}}</p>
      <el-alert v-if="historyError" :title="historyError" type="error" :closable="false"><el-button size="small" @click="loadHistoricalPreview()">重新读取选项</el-button></el-alert>
      <el-form label-position="top" v-loading="historyBusy"><el-form-item label="当前标准手机"><SharedCompatiblePhoneSelector v-model="historyPhoneIds" :multiple="false" @selected="chooseHistoricalPhone"/></el-form-item>
        <el-checkbox v-if="correctingHistoricalPhone" v-model="historySelection.confirmPhoneCorrection" aria-label="明确更正历史手机关联">明确更正原标准手机关联，原文保留并记录本次原因</el-checkbox>
        <el-form-item label="实际产品线"><el-select v-model="historySelection.productLineId" aria-label="历史关联产品线" @change="changeHistoricalProduct"><el-option v-for="line in historyPreview?.productLines||options?.productLines" :key="line.productId" :value="line.productId" :label="line.productName"/></el-select></el-form-item>
        <el-form-item label="已存在的型号项目"><el-select v-model="historySelection.projectId" filterable clearable aria-label="历史关联已有项目" @change="changeHistoricalProject"><el-option v-for="project in historyPreview?.projects" :key="project.projectId" :value="project.projectId" :disabled="!!project.blockedReason" :label="`${project.productName}${project.blockedReason?' · '+project.blockedReason:''}`"/></el-select><small v-if="!historyBusy&&(!historySelection.phoneModelId||!historySelection.productLineId)">请选择当前标准手机和实际产品线；项目、主档、具体套可分层补齐。</small><small v-else-if="!historyBusy&&!historyError&&historyPreview&&!historyPreview.projects.length">此产品线暂无获授权的已有项目；可以先保存其他明确关系，或使用集中补链登记已核实项目。</small></el-form-item>
        <el-form-item label="已存在的模具主档"><el-select v-model="historySelection.itemId" filterable clearable aria-label="历史关联模具主档" @change="historySelection.moldSetId=null"><el-option v-for="item in historyPreview?.items" :key="item.itemId" :value="item.itemId" :disabled="!!historyProject?.linkedItemId&&historyProject.linkedItemId!==item.itemId" :label="`${item.moldName} · 原刻码 ${item.engravedCode||'待核对'}`"/></el-select></el-form-item>
        <el-form-item label="该主档已存在的实物套"><el-select v-model="historySelection.moldSetId" filterable clearable aria-label="历史关联实物套"><el-option v-for="set in historySets" :key="set.moldSetId" :value="set.moldSetId" :disabled="!!set.blockedReason" :label="`第 ${set.setNo} 套 · ${set.setName} · PC ${set.pcCode||'原码待核对'} / TPU ${set.tpuCode||'原码待核对'}${set.blockedReason?' · '+set.blockedReason:''}`"/></el-select><small v-if="historySelection.itemId&&!historySets.length">该主档暂无获授权的已有实物套；不会根据原编码自动补造。</small></el-form-item>
        <p v-for="set in historySets.filter(s=>s.blockedReason)" :key="set.moldSetId">第 {{set.setNo}} 套：{{set.blockedReason}} <el-button size="small" link @click="router.push({path:'/molds',query:{moldSetId:set.moldSetId}})">核对该套实物</el-button></p>
        <el-form-item label="核对依据／更正原因" required><el-input v-model="historySelection.reason" type="textarea" :rows="3" :maxlength="4000" aria-label="历史关联核对原因" placeholder="说明原申请与所选现有资料的关系及核对依据"/></el-form-item>
      </el-form><template #footer><el-button @click="historyOpen=false">取消</el-button><el-button type="primary" :disabled="!historyCanSave" :loading="historyBusy" @click="saveHistoricalAssociation">保存已有资料关联</el-button></template>
    </el-dialog>
    <MoldHistoryReconciliation v-model="historyReview"/>
  </section>
</template>

<style scoped lang="scss">
.mold-applications{font-size:12px;--el-font-size-base:12px;height:100%;min-height:0;min-width:0;box-sizing:border-box;display:flex;flex-direction:column;gap:4px;padding:10px;background:var(--el-bg-color);container-type:inline-size}
.page-head{display:flex;align-items:center;justify-content:space-between;gap:8px;flex:none}
.page-head h2{margin:0;font-size:16px;white-space:nowrap}
.tools{display:flex;gap:6px;align-items:center;flex-wrap:wrap;margin:10px 0}
.page-head .tools{flex-wrap:nowrap;margin:0}
.page-head .el-button,.filters .el-button{height:28px;font-size:12px}
.filters :deep(.el-input__wrapper){height:28px;box-sizing:border-box}
.filters :deep(.el-input__inner){height:26px;line-height:26px}
.tools .el-button+.el-button,.filters .el-button+.el-button{margin-left:0}
.admin-tools{display:flex;flex-direction:column;align-items:stretch}
.admin-tools .el-button+.el-button{margin-left:0}
.status-tabs{display:flex;gap:4px;flex:none}
.status-tabs button{flex:1;min-width:0;height:28px;box-sizing:border-box;padding:4px;border:1px solid transparent;border-radius:4px;background:var(--el-fill-color-light);color:var(--el-text-color-regular);font:inherit;font-size:12px;white-space:nowrap;cursor:pointer}
.status-tabs button[aria-selected=true]{color:var(--el-color-primary);background:var(--el-color-primary-light-9);border-color:var(--el-color-primary-light-7)}
.status-tabs span{font-size:12px;font-variant-numeric:tabular-nums}
.filters{display:flex;gap:6px;align-items:center;flex:none}
.filters>.el-input{flex:1;min-width:0}
.advanced-filters{display:flex;gap:6px;flex-wrap:wrap;align-items:center;flex:none}
.advanced-filters>.el-select{flex:1;min-width:130px}
.advanced-filters>.el-date-editor{width:250px;max-width:100%;flex-grow:1}
.application-list{flex:1;min-height:0;min-width:0;display:flex;flex-direction:column;border:1px solid var(--el-border-color-lighter);border-radius:4px;overflow:hidden}
.application-row{display:grid;grid-template-columns:minmax(0,1.8fr) minmax(0,1.25fr) minmax(0,.95fr) minmax(0,1.5fr) minmax(0,1.2fr) minmax(0,.85fr) 60px;gap:8px;align-items:center;min-height:42px;box-sizing:border-box;padding:4px 8px;font-size:12px;line-height:17px}
.application-row>div,.application-row>span{min-width:0;overflow-wrap:anywhere}
.list-head{min-height:30px;background:var(--el-fill-color-light);color:var(--el-text-color-secondary);flex:none;border-bottom:1px solid var(--el-border-color-lighter)}
.list-head .application-row{min-height:30px}
.list-body{flex:1;min-height:0;overflow-y:auto;overflow-x:hidden;scrollbar-gutter:stable}
.application-record{border-bottom:1px solid var(--el-border-color-lighter)}
.application-record:nth-child(even){background:var(--el-fill-color-lighter)}
.application-record:hover{background:var(--el-color-primary-light-9)}
.record-identity .el-button{display:flex;height:auto;padding:0;border:0;max-width:100%;white-space:normal;text-align:left;line-height:16px}
.record-identity :deep(.el-button>span){overflow-wrap:anywhere;min-width:0}
.record-status{white-space:normal}.record-status :deep(.el-tag){height:auto;white-space:normal;padding:2px 4px;line-height:16px}
.record-codes{display:flex;flex-wrap:wrap;gap:0 8px}
.record-codes span{display:block;font-variant-numeric:tabular-nums}
.record-person{display:flex;flex-wrap:wrap;gap:0 4px}
small{display:block;color:var(--el-text-color-secondary);font-size:12px;line-height:17px}
.compact-label{display:none}
.el-pagination{--el-pagination-button-height:28px;justify-content:flex-end;flex:none;margin:0;min-width:0}
.el-pagination :deep(.el-select__wrapper){min-height:28px}
.source-record{display:flex;flex-wrap:wrap;gap:4px 12px;padding:4px 0;overflow-wrap:anywhere}
.photo-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}
.photo-grid .el-image{width:100%;height:130px;border:1px solid var(--el-border-color-lighter)}
.el-descriptions{margin-top:18px}
h3{margin:24px 0 16px;font-size:16px}
@container (max-width:1000px){
  .list-head{display:none}
  .application-row{grid-template-columns:minmax(0,1.2fr) minmax(0,1.35fr) 100px;grid-template-areas:"identity identity status" "tipo codes codes" "person date project";gap:2px 8px;padding:5px 8px}
  .record-identity{grid-area:identity}.record-tipo{grid-area:tipo;color:var(--el-text-color-secondary);font-size:12px}.record-status{grid-area:status;justify-self:end}.record-codes{grid-area:codes;display:flex;gap:2px 8px;flex-wrap:wrap;font-size:12px}.record-person{grid-area:person;display:flex;gap:4px;flex-wrap:wrap;font-size:12px}.record-date{grid-area:date;display:flex;gap:4px;flex-wrap:wrap;font-size:12px}.record-project{grid-area:project;justify-self:end}.compact-label{display:inline}
}
@container (max-width:400px){
  .application-row{grid-template-columns:minmax(0,1fr);grid-template-areas:"identity" "status" "tipo" "codes" "person" "date" "project"}
  .record-status,.record-project{justify-self:start}
}
@media(max-width:800px){.photo-grid{grid-template-columns:repeat(2,1fr)}}
.el-descriptions :deep(.el-descriptions__table){table-layout:fixed}
.el-descriptions :deep(.el-descriptions__label){width:160px}
.el-descriptions :deep(.el-descriptions__cell){overflow-wrap:anywhere}
</style>

