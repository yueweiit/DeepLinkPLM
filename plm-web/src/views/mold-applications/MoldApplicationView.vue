<script setup lang="ts">
import { t as uiText } from '@/locales'

import MoldHistoryReconciliation from '@/views/mold-applications/components/MoldHistoryReconciliation.vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter, useRoute } from 'vue-router'
import {useCollaborationWorkspace} from '@/stores/collaborationWorkspace'
import { useUserStore } from '@/stores/user'
import * as api from '@/api/modules/moldApplications'
import { phoneMemberName } from '@/utils/phoneMembers'
import MoldApplicationDetailDrawer from './components/MoldApplicationDetailDrawer.vue'
import './application-drawer.scss'

const router=useRouter(),route=useRoute(),user=useUserStore(),workspace=useCollaborationWorkspace()
let loadEpoch=0
const detailDrawer=ref<InstanceType<typeof MoldApplicationDetailDrawer>>()
const historyReview=ref(false)
const options=ref<api.MoldOptions>(),rows=ref<api.MoldApplication[]>([]),summary=ref<api.MoldSummary>(),total=ref(0)
const deletingId=ref<number|null>(null)
const loading=ref(false),busy=ref(false),error=ref(''),enabled=ref(true)
const query=reactive<api.MoldQuery>({page:1,size:50,status:'',projectId:Number(route.query.projectId)||undefined,unlinked:route.query.unlinked==='1'||undefined}),dateRange=ref<string[]>([]),filtersOpen=ref(false)
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
const researchProjectId=(row:api.MoldApplication)=>row.sourceSnapshot?.research?.projectId
function openProject(row:api.MoldApplication){return router.push({path:researchProjectId(row)?'/product-research':'/projects',query:{productId:String(researchProjectId(row)||row.projectId)}})}
const tipoOptions=computed(()=>options.value?.controls.find(c=>c.key==='tipo')?.options||[])
async function load(){const auth=user.token,epoch=++loadEpoch;loading.value=true;error.value='';try{const [page,stats]=await Promise.all([api.getMoldApplications({...query,from:dateRange.value[0],until:dateRange.value[1]}),api.getMoldSummary({projectId:query.projectId,unlinked:query.unlinked})]);if(auth!==user.token||epoch!==loadEpoch)return;rows.value=page.content;total.value=page.totalElements;summary.value=stats}catch(e){if(auth===user.token&&epoch===loadEpoch)error.value=e instanceof Error?e.message:'读取失败，请重试'}finally{if(auth===user.token&&epoch===loadEpoch)loading.value=false}}
function filter(){query.page=1;void load()}
async function start(){const auth=user.token;try{const config=await api.getMoldApplicationConfig();if(auth!==user.token)return;enabled.value=config.enabled;if(!enabled.value)return;const next=await api.getMoldOptions();if(auth!==user.token)return;options.value=next;await load();await openRoute()}catch{if(auth===user.token)error.value='无法读取开模申请，请检查登录权限并重试'}}
async function open(id:number){await detailDrawer.value?.open(id)}
async function attempt(action:()=>Promise<unknown>){try{await action()}catch(e){if(e==='cancel'||e==='close')return;error.value=e instanceof Error?e.message:'操作失败，请核对后重试';ElMessage.error(error.value)}}
function openRow(row:api.MoldApplication){void attempt(()=>open(row.applicationId))}
function edit(row?:api.MoldApplication){void router.push({path:'/market-watch',query:row?{applicationId:row.applicationId,edit:'1'}:{new:'1'}})}
async function openRoute(){openConfigurationRoute();query.projectId=Number(route.query.projectId)||undefined;query.unlinked=route.query.unlinked==='1'||undefined;const id=Number(route.query.applicationId);if(route.query.edit==='1'||route.query.phoneModelId){await router.replace({path:'/market-watch',query:{...route.query}});return}if(Number.isSafeInteger(id)&&id>0)await detailDrawer.value?.open(id,{reviewHistorical:route.query.review==='historical'})}
watch(()=>[route.query.projectId,route.query.unlinked],()=>{query.projectId=Number(route.query.projectId)||undefined;query.unlinked=route.query.unlinked==='1'||undefined;if(options.value){query.page=1;void load()}})
watch(()=>route.fullPath,()=>{if(options.value)void attempt(openRoute)})
async function removeDraft(row:api.MoldApplication){if(deletingId.value!==null)return;const auth=user.token;deletingId.value=row.applicationId;try{await detailDrawer.value?.removeDraft(row)}finally{if(auth===user.token)deletingId.value=null}}
async function deleted(id:number){rows.value=rows.value.filter(r=>r.applicationId!==id);if(Number(route.query.applicationId)===id){const next={...route.query};delete next.applicationId;delete next.review;await router.replace({path:route.path,query:next})}query.page=1;await load()}
async function syncAll(){busy.value=true;try{const result=await api.syncMoldApplications('2026-01-01',new Date().toLocaleDateString('sv-SE',{timeZone:'Asia/Shanghai'}));ElMessage.success(uiText('已同步 {value0} 条，失败 {value1} 条',{value0:(result.synced),value1:(result.failedInstanceIds.length)}));workspace.changed();options.value=await api.getMoldOptions();await load()}finally{busy.value=false}}
const configOpen=ref(false),configTipo=ref(''),configLine=ref<number>(),configPrefix=ref(''),configMaterials=reactive({tpu:'10',pc:'20'}),configVersion=ref<number>()
function chooseTipo(tipo:string){const c=options.value?.configs.find(c=>c.tipo===tipo);configLine.value=c?.productLineId;configPrefix.value=c?.productSpecificCode||'';configVersion.value=c?.version;configMaterials.tpu=c?.materials.find(m=>m.partType==='TPU')?.code||'10';configMaterials.pc=c?.materials.find(m=>m.partType==='PC')?.code||'20'}
function chooseLine(line:number){configPrefix.value=options.value?.productLines.find(p=>p.productId===line)?.productSpecificCode||''}
function openConfigurationRoute(){if(route.query.configure!=='1')return;if(!options.value?.administrator){error.value='产品线配置仅管理员可维护，请联系管理员补充当前审批 Tipo 与产品线映射。';return}const line=Number(route.query.productLineId);if(!options.value.productLines.some(p=>p.productId===line)){error.value='产品不存在或无查看权限，请重新选择产品线。';return}const config=options.value.configs.find(c=>c.productLineId===line&&tipoOptions.value.includes(c.tipo));configTipo.value=config?.tipo||'';chooseTipo(configTipo.value);configLine.value=line;chooseLine(line);configOpen.value=true}
async function saveConfig(){if(!configLine.value)return;busy.value=true;try{await api.saveMoldConfig({tipo:configTipo.value,productLineId:configLine.value,productSpecificCode:configPrefix.value,materials:[...(configMaterials.tpu?[{code:configMaterials.tpu,partType:'TPU'}]:[]),...(configMaterials.pc?[{code:configMaterials.pc,partType:'PC'}]:[])],...(configVersion.value?{version:configVersion.value}:{})});options.value=await api.getMoldOptions();chooseTipo(configTipo.value);ElMessage.success(uiText('产品线配置已保存'));configOpen.value=false}finally{busy.value=false}}
onMounted(start);watch(()=>user.token,()=>{++loadEpoch;deletingId.value=null;loading.value=false;busy.value=false;rows.value=[];total.value=0;summary.value=undefined;options.value=undefined;configOpen.value=false})
</script>

<template>
  <section class="mold-applications" v-loading="busy">
    <header class="page-head"><h2>{{ uiText("开模申请进度") }}</h2><div class="tools"><el-button v-if="options?.canConvert" size="small" @click="historyReview=true">{{ uiText("历史补链与缺项") }}</el-button><el-button size="small" @click="start">{{ uiText("刷新") }}</el-button><el-popover v-if="options?.administrator" trigger="click" placement="bottom-end" :width="150" :persistent="false"><template #reference><el-button size="small">{{ uiText("更多") }}</el-button></template><div class="admin-tools"><el-button text @click="configOpen=true">{{ uiText("产品线配置") }}</el-button><el-button text @click="attempt(syncAll)">{{ uiText("同步钉钉") }}</el-button></div></el-popover><el-button v-if="options?.canCreate" size="small" type="primary" @click="edit()">{{ uiText("发起申请") }}</el-button></div></header>
    <el-alert v-if="!enabled" :title='uiText("开模申请暂未启用")' type="info" :closable="false" />
    <template v-else>
      <div class="status-tabs" role="tablist" :aria-label='uiText("申请状态")'><button role="tab" :aria-selected="!query.status" @click="selectStage('')">{{ uiText("全部 ") }}<span>{{ totalCount }}</span></button><button v-for="s in stages" :key="s.value" role="tab" :aria-selected="query.status===s.value" @click="selectStage(s.value)">{{ uiText(String((s.label) ?? '')) }} <span>{{ count(s.value) }}</span></button></div>
      <div class="filters"><el-input v-model="query.keyword" size="small" :aria-label='uiText("申请关键词")' :placeholder='uiText("型号 / 编码 / 审批编号")' clearable @keyup.enter="filter"/><el-button size="small" @click="filter">{{ uiText("查询") }}</el-button><el-button size="small" :aria-expanded="filtersOpen" aria-controls="mold-advanced-filters" @click="filtersOpen=!filtersOpen">{{ uiText("筛选") }}{{ activeFilters?` · ${activeFilters}`:'' }}</el-button></div>
      <div v-if="filtersOpen" id="mold-advanced-filters" class="advanced-filters"><el-select v-model="query.tipo" size="small" :aria-label='uiText("Tipo筛选")' :placeholder='uiText("全部 Tipo")' clearable filterable @change="filter"><el-option v-for="t in tipoOptions" :key="t" :label="t" :value="t"/></el-select><el-select v-model="query.originator" size="small" :aria-label='uiText("发起人筛选")' :placeholder='uiText("全部发起人")' clearable @change="filter"><el-option v-for="p in options?.originators" :key="p.id" :label="p.name" :value="p.id"/></el-select><el-date-picker v-model="dateRange" size="small" type="daterange" value-format="YYYY-MM-DD" start-placeholder="发起日期" end-placeholder="截至日期" @change="filter"/><el-button size="small" text @click="resetFilters">{{ uiText("清空筛选") }}</el-button></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button text @click="start">{{ uiText("重试") }}</el-button></el-alert>
      <div class="application-list" role="table" :aria-label='uiText("开模申请列表")' v-loading="loading">
        <div role="rowgroup" class="list-head"><div role="row" class="application-row"><span role="columnheader">{{ uiText("申请 / 型号") }}</span><span role="columnheader">{{ uiText("Tipo / 产品类型") }}</span><span role="columnheader">{{ uiText("状态") }}</span><span role="columnheader">{{ uiText("模具编码") }}</span><span role="columnheader">{{ uiText("发起人 / 日期") }}</span><span role="columnheader">{{ uiText("期望交付") }}</span><span role="columnheader">{{ uiText("项目／操作") }}</span></div></div>
        <div role="rowgroup" class="list-body" tabindex="0" :aria-label='uiText("申请记录，垂直滚动查看更多")'>
          <div v-for="row in rows" :key="row.applicationId" role="row" class="application-row application-record" @dblclick="openRow(row)">
            <div role="cell" class="record-identity"><el-button link type="primary" @click="openRow(row)">{{ currentPhoneName(row) }}</el-button><small v-if="row.standardPhone||row.phoneModelCode">{{row.standardPhone?uiText('手机'):uiText('历史手机编码')}} {{currentPhoneCode(row)}}</small></div>
            <div role="cell" class="record-tipo">{{ row.tipo||'—' }}</div>
            <div role="cell" class="record-status"><el-tag size="small" :type="stageType(row)">{{ uiText(label(row)) }}</el-tag><small v-if="hasHistoricalAssociation(row)">{{ uiText("已补明确历史关系") }}</small><small v-if="row.executionStatus==='paused'&&!hasHistoricalAssociation(row)">{{ uiText("开模暂停") }}</small><small v-else-if="row.executionStatus==='terminated'&&!hasHistoricalAssociation(row)">{{ uiText("工程已终止") }}</small><small v-if="row.provisioningStatus==='blocked'">{{ uiText("建档待处理") }}</small></div>
            <div role="cell" class="record-codes"><span v-for="code in moldCodes(row)" :key="code">{{ code }}</span><span v-if="!row.generatedCodes">—</span></div>
            <div role="cell" class="record-person"><span>{{ row.originatorName||uiText('本人草稿') }}</span><small>{{ chinaTime(row.sourceCreatedAt||row.createdAt,true)||'—' }}</small></div>
            <div role="cell" class="record-date"><small class="compact-label">{{ uiText("交期") }}</small>{{ row.form.expectedDeliveryDate||'—' }}</div>
            <div role="cell" class="record-project"><el-button v-if="row.projectId||researchProjectId(row)" size="small" link @click="openProject(row)">{{ uiText(researchProjectId(row)?"打开新品项目":"打开项目") }}</el-button><el-button v-if="row.canDeleteDraft" link size="small" type="danger" :loading="deletingId===row.applicationId" :disabled="deletingId!==null" @click.stop="removeDraft(row)">{{ uiText("删除草稿") }}</el-button><small v-else-if="!row.projectId&&!researchProjectId(row)">{{ uiText("未转入") }}</small></div>
          </div><el-empty v-if="!rows.length&&!loading" :description='uiText("没有符合条件的申请")' :image-size="64"/>
        </div>
      </div><el-pagination size="small" v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[20,50,100]" :pager-count="5" layout="sizes, prev, pager, next" @current-change="load" @size-change="filter"/>
    </template>
    <MoldApplicationDetailDrawer ref="detailDrawer" :options="options" @edit="edit" @changed="load" @deleted="deleted" @error="error=$event" @other-phone="projectId=>router.push({path:'/product-research',query:{productId:projectId}})"/>
    <el-dialog v-model="configOpen" :title='uiText("Tipo 与 PLM 产品线配置")' width="620px"><el-form label-position="top"><el-form-item label="Tipo"><el-select v-model="configTipo" @change="chooseTipo"><el-option v-for="t in tipoOptions" :key="t" :value="t" :label="t"/></el-select></el-form-item><el-form-item :label='uiText("产品线")'><el-select v-model="configLine" filterable @change="chooseLine"><el-option v-for="p in options?.productLines" :key="p.productId" :value="p.productId" :label="uiText('{value0} · {value1}',{value0:(p.productName),value1:(p.productSpecificCode||'无编码')})"/></el-select></el-form-item><el-form-item :label='uiText("产品特定编码（来自产品线）")'><el-input v-model="configPrefix" readonly/></el-form-item><el-form-item :label='uiText("TPU 两位材质编码")'><el-input v-model="configMaterials.tpu" maxlength="2"/></el-form-item><el-form-item :label='uiText("PC 两位材质编码（没有则留空）")'><el-input v-model="configMaterials.pc" maxlength="2"/></el-form-item></el-form><template #footer><el-button @click="attempt(saveConfig)">{{ uiText("保存配置") }}</el-button></template></el-dialog>
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
@container (max-width:1000px){
  .list-head{display:none}
  .application-row{grid-template-columns:minmax(0,1.2fr) minmax(0,1.35fr) 100px;grid-template-areas:"identity identity status" "tipo codes codes" "person date project";gap:2px 8px;padding:5px 8px}
  .record-identity{grid-area:identity}.record-tipo{grid-area:tipo;color:var(--el-text-color-secondary);font-size:12px}.record-status{grid-area:status;justify-self:end}.record-codes{grid-area:codes;display:flex;gap:2px 8px;flex-wrap:wrap;font-size:12px}.record-person{grid-area:person;display:flex;gap:4px;flex-wrap:wrap;font-size:12px}.record-date{grid-area:date;display:flex;gap:4px;flex-wrap:wrap;font-size:12px}.record-project{grid-area:project;justify-self:end}.compact-label{display:inline}
}
@container (max-width:400px){
  .application-row{grid-template-columns:minmax(0,1fr);grid-template-areas:"identity" "status" "tipo" "codes" "person" "date" "project"}
  .record-status,.record-project{justify-self:start}
}
</style>

