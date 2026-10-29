<script setup lang="ts">
import MoldHistoryReconciliation from '@/views/mold-applications/components/MoldHistoryReconciliation.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getWorkbenchProjects, getProjectBusinessOverview, type WorkbenchProject, type WorkbenchPage, type ProjectOverview, type ProjectView, type ProjectKind } from '@/api/modules/projectWorkbench'
import { getMoldApplications, moldBusinessStatusNames, type MoldApplication } from '@/api/modules/moldApplications'
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace'
import { useFinanceWorkspace } from '@/stores/financeWorkspace'
import { acceptanceNames, locationNames } from '@/utils/collaboration'
import { phoneCoverageNames } from '@/utils/phoneCoverage'
import { moldSetPhones } from '@/utils/moldPresentation'
import {toArchivedSkuRoute,toArchivedProductRoute} from '@/utils/projectRoute'
import { useUserStore } from '@/stores/user'
const getErrorMessage=(e:unknown)=>e instanceof Error?e.message:'项目读取失败，请重试'
const historyReview=ref(false)
const route=useRoute(),router=useRouter(),workspace=useCollaborationWorkspace(),finance=useFinanceWorkspace(),user=useUserStore()
const view=ref<ProjectView|'unlinked'>(route.query.tab==='archived'?'completed':route.query.tab==='abandoned'?'stopped':route.query.tab==='all_projects'?'all':'active'),kind=ref<'all'|ProjectKind>('all'),keyword=ref(''),page=ref(1),size=ref(50),loading=ref(false),error=ref('')
const data=ref<WorkbenchPage>(),unlinked=ref<MoldApplication[]>([]),unlinkedTotal=ref(0),overview=ref<ProjectOverview>(),drawer=ref(false),detailLoading=ref(false)
const rows=computed(()=>data.value?.content||[]),total=computed(()=>view.value==='unlinked'?unlinkedTotal.value:data.value?.totalElements||0)
const kindName=(kind:string|null)=>kind==='product_research'?'新品研发':kind==='model_extension'?'型号研发':'历史产品资料'
const date=(value:string|null|undefined)=>value?.slice(0,10)||'未填写'
const count=(value:number|null)=>value===null?'未授权':value
const tabs:{value:ProjectView|'unlinked';label:string}[]=[{value:'active',label:'进行中'},{value:'completed',label:'已完成'},{value:'stopped',label:'已停止'},{value:'all',label:'全部项目'},{value:'unlinked',label:'未建档申请'}]
let epoch=0,detailEpoch=0
async function load(){const current=++epoch,token=user.token;loading.value=true;error.value='';try{
  const result=await getWorkbenchProjects({page:page.value,size:size.value,keyword:keyword.value.trim()||undefined,kind:kind.value,view:view.value==='unlinked'?'active':view.value})
  if(current!==epoch||token!==user.token)return;data.value=result
  if(view.value==='unlinked'&&result.capabilities.applications){const apps=await getMoldApplications({page:page.value,size:size.value,keyword:keyword.value.trim()||undefined,unlinked:true});if(current===epoch&&token===user.token){unlinked.value=apps.content;unlinkedTotal.value=apps.totalElements}}
}catch(e){if(current===epoch)error.value=getErrorMessage(e)}finally{if(current===epoch)loading.value=false}}
async function open(id:number){const current=++detailEpoch,token=user.token;drawer.value=true;detailLoading.value=true;overview.value=undefined;try{const result=await getProjectBusinessOverview(id);if(current===detailEpoch&&token===user.token)overview.value=result}catch(e){if(current===detailEpoch)ElMessage.error(getErrorMessage(e))}finally{if(current===detailEpoch)detailLoading.value=false}}
function show(row:Pick<WorkbenchProject,'projectId'|'developmentMode'>){void router.replace({path:row.developmentMode==='launch_v1'?'/product-research':'/projects',query:{productId:row.projectId}})}
function close(){detailEpoch++;overview.value=undefined;if(route.query.productId&&!route.query.section)void router.replace({path:'/projects',query:{}})}
function go(path:string,query:Record<string,string|number>={}){void router.push({path,query})}
function application(app:MoldApplication){go(app.status==='draft'?'/market-watch':'/mold-applications',{applicationId:app.applicationId,...(app.status==='draft'?{edit:'1'}:{})})}
function section(name:string){if(overview.value){const target=overview.value.productType==='product_line'?name:({bom_manage:'bom',process_detail:'process'} as Record<string,string>)[name]||name;go('/projects',{productId:overview.value.projectId,section:target})}}
const physical=(value:unknown)=>value==='confirmed'?phoneCoverageNames.confirmed:value==='making'?phoneCoverageNames.making:phoneCoverageNames.needs_review
function sampleLedger(){if(overview.value)go('/samples',{projectId:overview.value.projectId,from:route.fullPath})}
function scoped(path:string){if(overview.value)go(path,{projectId:overview.value.projectId})}
function tabCount(value:ProjectView|'unlinked'){return value==='unlinked'?data.value?.unlinkedApplications?.total:data.value?.stats[value]}
function reset(){page.value=1;void load()}
function refresh(){void load();if(drawer.value&&overview.value)void open(overview.value.projectId)}
watch([view,kind,size],reset)
watch(page,()=>void load())
watch(()=>[route.query.productId,route.query.section],()=>{const id=Number(route.query.productId);if(id&&!route.query.section)void open(id);else {detailEpoch++;drawer.value=false;overview.value=undefined}},{immediate:true})
watch(()=>[workspace.revision,finance.forEntity('expense').revision,finance.forEntity('budget').revision],refresh)
watch(()=>user.token,()=>{epoch++;detailEpoch++;data.value=undefined;overview.value=undefined;drawer.value=false;unlinked.value=[];if(user.token)void load()})
onMounted(()=>void load())
</script>
<template>
  <section class="project-workbench" aria-label="项目中心">
    <header class="workbench-header"><h1>项目中心</h1><span>正式开发项目 · 新品研发 / 型号研发</span><div class="spacer"/><el-button v-if="user.hasPermission('mold-application:convert')" @click="historyReview=true">历史补链与缺项</el-button><el-button @click="go('/projects',{tab:'archived',archiveView:'product'})">产品与 SKU 资料</el-button><el-button @click="refresh">刷新</el-button></header>
    <div class="filters"><el-input v-model="keyword" clearable placeholder="搜索项目 / 产品 / 手机 / 编码 / 负责人" @keyup.enter="reset"/><el-select v-model="kind" aria-label="项目类型"><el-option label="全部类型" value="all"/><el-option label="新品研发" value="product_research"/><el-option label="型号研发" value="model_extension"/></el-select><el-button type="primary" @click="reset">查询</el-button><el-button v-if="data?.capabilities.applications" @click="go('/market-watch')">发起开模申请</el-button></div>
    <nav class="views" aria-label="项目视图"><button v-for="tab in tabs.filter(t=>t.value!=='unlinked'||data?.capabilities.applications)" :key="tab.value" :class="{active:view===tab.value}" @click="view=tab.value">{{tab.label}} <span v-if="tabCount(tab.value)!==undefined">{{tabCount(tab.value)}}</span></button></nav>
    <el-alert v-if="error" type="error" :title="error" :closable="false"/>
    <div class="project-list" v-loading="loading">
      <template v-if="view!=='unlinked'">
        <div v-if="rows.length" class="records" role="table" aria-label="正式开发项目">
          <div class="record record-head" role="row"><div>项目 / 类型</div><div>所属产品 / 标准手机</div><div>主进度 / 项目节点</div><div>申请 / 模具 / 具体套</div><div>负责人 / 交期</div><div>风险 / 下一步</div><div>操作</div></div>
          <div v-for="row in rows" :key="row.projectId" class="record project-record" role="row">
            <div><button class="text-link" @click="show(row)">{{row.projectName}}</button><small>{{kindName(row.projectKind)}} · {{row.projectCode}}</small></div>
            <div><span>{{row.productLineName||'产品待关联'}}</span><small>{{row.phoneName|| (row.projectKind==='product_research'?'新产品 / 平台':row.originalModel||'标准手机待关联')}}{{row.phoneName?' · '+(row.phoneModelCode||'编码待分配'):''}}</small></div>
            <div><span>{{row.phase}}</span><small>节点：{{row.flowNode}}</small></div>
            <div><span>申请 {{count(row.applicationCount)}} · 主档 {{count(row.archiveCount)}}</span><small>具体套 {{count(row.moldSetCount)}} · 实物 {{count(row.physicalSetCount)}}</small></div>
            <div><span>{{row.ownerName||'负责人待指定'}}</span><small>{{date(row.expectedDeliveryDate)}}</small></div>
            <div><span v-if="row.risks.length" class="risk">{{row.risks.join('；')}}</span><span>{{row.nextStep}}</span></div>
            <div><el-button link type="primary" @click="show(row)">业务总览</el-button></div>
          </div>
        </div>
        <div v-else-if="!loading&&!error" class="empty-projects"><h2>{{view==='active'?'暂无进行中的正式开发项目':'当前视图暂无正式开发项目'}}</h2><p>项目由正式立项或可信审批建档自动登记。历史归档产品、带颜色型号和 SKU 保留在基础资料中。</p><p>产品研发沿用现有研发节点；型号开发从开模申请进入，按实际制作、交付和验收事实汇总。</p><el-button v-if="data?.capabilities.applications" type="primary" @click="go('/market-watch')">查看市场机型并申请开模</el-button><el-button v-if="data?.capabilities.applications" @click="view='unlinked'">查看未建档申请</el-button><el-button @click="go('/dashboard')">前往首页立项</el-button></div>
      </template>
      <template v-else>
        <p class="unlinked-note">以下申请尚无明确项目关联。审批同意与项目建档分别展示，有真实任务同意依据的历史申请可一键补齐项目与明确关联；缺少的实物套不补造。</p>
        <div v-if="unlinked.length" class="application-records"><article v-for="app in unlinked" :key="app.applicationId" class="application-record"><div><strong>{{app.standardPhone?.modelName||app.model||app.title||'待完善手机'}}</strong><small>{{app.tipo||'款式待选'}} · {{app.approvalNo||'#'+app.applicationId}}</small></div><div>{{moldBusinessStatusNames[app.businessStatus||'approval_unknown']}}<small>{{app.applicantName||app.originatorName||'申请人待核对'}}</small></div><div>{{app.nextStep||'核对申请关联与建档依据'}}<small v-if="app.provisioningError" class="risk">{{app.provisioningError}}</small></div><el-button link type="primary" @click="application(app)">{{app.status==='draft'?'继续草稿':'查看申请进度'}}</el-button></article></div><el-empty v-else-if="!loading" description="暂无未建档申请"/>
      </template>
    </div>
    <footer class="pagination"><span>共 {{total}} 条</span><el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="[50,100]" :total="total" layout="sizes, prev, pager, next" size="small"/></footer>
    <el-drawer v-model="drawer" title="项目业务总览" size="min(940px, 96vw)" @closed="close">
      <div v-loading="detailLoading" class="overview"><template v-if="overview">
        <h2>{{overview.projectName}}</h2><p>{{kindName(overview.projectKind)}} · {{overview.projectCode}} · 登记 {{date(overview.registeredAt)}}</p>
        <el-alert v-if="overview.stopReason" :title="'项目已停止：'+overview.stopReason" type="warning" :closable="false"/>
        <el-alert v-if="overview.historicalRecord" title="此记录是历史产品资料，未登记为开发项目，不计入项目统计。" type="info" :closable="false"/>
        <dl><div><dt>所属产品</dt><dd>{{overview.productLineName||'待关联'}}</dd></div><div><dt>标准手机</dt><dd>{{overview.phoneName||'未关联'}} · {{overview.phoneModelCode||'编码待分配'}}</dd></div><div><dt>负责人 / 交期</dt><dd>{{overview.ownerName||'待指定'}} / {{date(overview.expectedDeliveryDate)}}</dd></div><div><dt>主进度</dt><dd>{{overview.phase}}</dd></div><div><dt>人工流程节点</dt><dd>{{overview.flowNode}} <el-button link type="primary" @click="section('project_flow')">查看流程</el-button></dd></div><div><dt>下一步</dt><dd>{{overview.nextStep}}</dd></div></dl>
        <el-alert v-for="risk in overview.risks" :key="risk" :title="risk" type="warning" :closable="false"/>
        <h3>来源申请</h3><p v-if="!overview.capabilities.applications">暂无读取权限</p><p v-else-if="!overview.applications.length">尚无明确关联的申请</p><article v-for="app in overview.applications" :key="app.applicationId" class="business-row"><span>{{app.approvalNo||'#'+app.applicationId}} · {{moldBusinessStatusNames[app.businessStatus||'approval_unknown']}} · {{app.nextStep}}</span><el-button link type="primary" @click="application(app)">办理申请</el-button></article>
        <h3>模具档案与具体套</h3><p v-if="!overview.capabilities.molds&&!overview.capabilities.schedules">暂无读取权限</p><article v-for="item in overview.archives" :key="item.itemId" class="business-row"><span>{{item.moldName}} · 原刻码 {{item.engravedCode||'待核对'}}</span><el-button link @click="go('/molds',{itemId:item.itemId})">模具主档</el-button></article><p v-if="overview.capabilities.schedules&&!overview.moldSets.length">尚无明确归属的具体套；主档关联不代表全部实物套属于本项目。</p><article v-for="set in overview.moldSets" :key="set.moldSetId" class="business-row"><span>{{set.assetNumber}} · {{set.setName}}<small>{{moldSetPhones(set)}}<br/>PC：{{physical(set.pcPhysicalStatus)}} / {{acceptanceNames[String(set.pcAcceptanceStatus)]||'未验收'}} / {{locationNames[String(set.pcCurrentLocation)]||'位置待填'}} · TPU：{{physical(set.tpuPhysicalStatus)}} / {{acceptanceNames[String(set.tpuAcceptanceStatus)]||'未验收'}} / {{locationNames[String(set.tpuCurrentLocation)]||'位置待填'}}</small></span><el-button v-if="overview.capabilities.molds" link @click="go('/molds',{moldSetId:Number(set.moldSetId)})">实物详情</el-button><el-button link type="primary" @click="go('/mold-schedules',{moldSetId:Number(set.moldSetId),projectId:overview.projectId})">逐套排程</el-button></article>
        <h3>工程资料与费用</h3><el-button v-if="user.hasPermission('partner:view')" @click="sampleLedger">实物样品台账</el-button><div class="business-links"><el-button v-if="overview.capabilities.documents" @click="scoped('/files')">图纸与文件 ({{overview.documentCount}})</el-button><el-button v-if="overview.capabilities.bom" @click="section('bom_manage')">项目 BOM ({{overview.bomCount}})</el-button><el-button v-if="overview.capabilities.process" @click="section('process_detail')">项目工艺</el-button><el-button v-if="overview.capabilities.finance" @click="scoped('/development-expenses')">关联费用 ({{overview.relatedExpenseCount}})</el-button><el-button @click="section('basic')">产品资料</el-button></div>
        <template v-if="overview.directFinance"><p>项目直接归属费用，按原币展示；共用档案及套的费用仅在关联费用中查询，不重复计入。</p><p v-if="!overview.directFinance.currencyGroups.length">暂无项目直接归属费用</p><p v-for="money in overview.directFinance.currencyGroups" :key="money.currencyCode">{{money.currencyCode}}：申请 {{money.requestedTotal??'未填写'}} / 批准 {{money.approvedTotal??'未填写'}} / 申报 {{money.declaredTotal??'未填写'}} / ERP 入账净额 {{money.erpPostedNet??'未填写'}}</p></template>
        <template v-if="overview.projectKind==='product_research'"><h3>关联型号开发项目</h3><p v-if="!overview.childProjects.length">暂无明确关联的型号开发项目</p><article v-for="child in overview.childProjects" :key="child.projectId" class="business-row"><button class="text-link" @click="show(child)">{{child.projectName}}</button></article><p>产品研发围绕需求立项、设计打样、研发验证、BOM／工艺定型与试产发布；本轮继续使用现有流程与资料办理入口。</p></template>
      </template></div>
    </el-drawer>
    <MoldHistoryReconciliation v-model="historyReview"/>
  </section>
</template>
<style scoped>
.project-workbench{display:flex;flex-direction:column;height:100%;min-height:0;min-width:0;gap:8px;font-size:12px;container-type:inline-size}.workbench-header,.filters,.views,.pagination,.business-links{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.workbench-header h1{font-size:16px;margin:0}.workbench-header>span,small,dt{color:#64748b}.spacer{flex:1}.filters .el-input{flex:1;min-width:150px}.filters .el-select{width:140px}.views button{border:0;background:#edf1f6;padding:5px 9px;border-radius:4px;color:#475569;cursor:pointer}.views button.active{background:#e5edff;color:#2563eb}.project-list{flex:1;min-height:0;overflow:auto}.record{display:grid;grid-template-columns:1.2fr 1.15fr 1.05fr .9fr .7fr 1.2fr 80px;gap:8px;align-items:center;padding:4px 8px;border-bottom:1px solid #e5eaf2;min-height:42px;line-height:16px;background:white;box-sizing:border-box}.record>div{min-width:0;overflow-wrap:anywhere}.record-head{min-height:32px;background:#f2f5fa;color:#64748b;position:sticky;top:0;z-index:1}.record:nth-child(odd){background:#f8faff}.record small,.business-row small,.application-record small{display:block;font-size:12px;line-height:16px}.text-link{padding:0;border:0;background:none;color:#2563eb;text-align:left;font:inherit;cursor:pointer}.risk{color:#b45309}.project-record .risk{display:block}.pagination{justify-content:space-between;flex-shrink:0;min-height:32px}.empty-projects{padding:50px 20px;text-align:center;color:#64748b}.empty-projects h2{font-size:16px;color:#334155}.overview{font-size:12px}.overview h2{font-size:16px}.overview h3{font-size:14px;margin-top:22px}.overview dl{display:grid;grid-template-columns:1fr 1fr;gap:12px}.overview dd{margin:3px 0;overflow-wrap:anywhere}.business-row{display:flex;gap:8px;align-items:center;justify-content:space-between;padding:8px 0;border-bottom:1px solid #e5eaf2}.business-row>span{flex:1;min-width:0;overflow-wrap:anywhere}.unlinked-note{margin:4px 0 8px;color:#64748b}.application-record{display:grid;grid-template-columns:1.2fr .8fr 1.5fr 110px;gap:8px;padding:4px 8px;min-height:42px;border-bottom:1px solid #e5eaf2;background:white;align-items:center;box-sizing:border-box}.application-record>div{min-width:0;overflow-wrap:anywhere}:deep(.el-button),:deep(.el-input__wrapper),:deep(.el-select__wrapper){min-height:28px;height:28px;font-size:12px}:deep(.el-pagination){--el-pagination-font-size:12px}:deep(.el-drawer__body){padding:12px}
@container(max-width:1100px){.record-head{display:none}.project-record{grid-template-columns:1fr 1fr;gap:6px;padding:10px;align-items:start}.project-record>div:last-child{grid-column:2;justify-self:end}.application-record{grid-template-columns:1fr 1fr;gap:6px;padding:8px}.workbench-header>span{display:none}.filters .el-select{width:115px}}
</style>
