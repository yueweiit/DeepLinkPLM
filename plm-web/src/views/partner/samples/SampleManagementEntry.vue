<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,nextTick,onBeforeUnmount,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {Filter,Location,Plus,Search} from '@element-plus/icons-vue'
import {useUserStore} from '@/stores/user'
import {sampleIdentities,sampleIdentity,sampleIdentityOptions,querySampleIdentities} from '@/api/modules/sampleManagement'
import type {SampleIdentity,SampleLabel,SamplePage,SampleSupplierOption,SampleLocationSite,SampleLocationOption} from '@/types/sampleManagement'
import PartnerWorkspace from '../PartnerWorkspace.vue'
import SampleLedgerTable from './SampleLedgerTable.vue'
import PartnerFiles from '../PartnerFiles.vue'
import CatalogCurrentInfo from '../catalog/CatalogCurrentInfo.vue'
import SampleLabelPrint from './SampleLabelPrint.vue'
import SampleShipments from './SampleShipments.vue'
import SampleBatchActionDialog from './SampleBatchActionDialog.vue'
import SampleLocationSetDialog from './SampleLocationSetDialog.vue'
import SampleLocationManager from './SampleLocationManager.vue'
import {useSampleLocationOptions} from './useSampleLocationOptions'
import {sampleLocationAccess,sampleLocationLabel,samplePositionKey,sampleLocationSites} from '@/utils/sampleLocation'
import SampleColorCorrectionDialog from './SampleColorCorrectionDialog.vue'
import SampleReceiptCorrectionDialog from './SampleReceiptCorrectionDialog.vue'
import {ElMessage} from 'element-plus'
import {sampleRoutes} from '@/utils/sampleDomains'
import {saveBlob} from '@/utils/file'
const route=useRoute(),router=useRouter(),user=useUserStore()
const access=computed(()=>sampleLocationAccess(user)),localOnly=computed(()=>access.value.localOnly),locationOptions=useSampleLocationOptions()
const legacy=computed(()=>!localOnly.value&&!!(route.query.id||route.query.new||route.query.sourceType||route.query.projectId||route.query.modelProductId||route.query.productId||route.query.customerId||route.query.customerContext||route.query.view==='batches'))
const mode=computed(()=>route.query.view==='shipments'&&(!localOnly.value||access.value.canWrite)?'shipments':'identities'),domain=computed(()=>String(route.meta.sampleDomain||route.query.domain||''))
const data=ref<SamplePage<SampleIdentity>>(),detail=ref<SampleIdentity>(),keyword=ref(''),appliedKeyword=ref(''),page=ref(1),loading=ref(false),error=ref(''),selected=ref(new Map<number,SampleIdentity>()),printOpen=ref(false),labels=ref<SampleLabel[]>([]),batchChoice=ref(false),pendingAction=ref('')
const bulkAction=ref<'loan'|'return'|'move'>(),bulkRows=ref<SampleIdentity[]>([]),colorEditing=ref(false)
const receiptAction=ref<'correct_receipt'|'void_receipt'>(),receiptRows=ref<SampleIdentity[]>([]),receiptStatus=ref('active')
const filtersOpen=ref(true)
const ledgerTable=ref<InstanceType<typeof SampleLedgerTable>>()
const pageSizes=[50,200,1000,2000],pageSize=ref(50),customSizeOpen=ref(false),sizeDraft=ref('50'),sizeError=ref(false)
const filterCount=computed(()=>Number(Boolean(supplierCode.value))+Number(Boolean(site.value&&!localOnly.value))+Number(Boolean(locationId.value))+Number(unlocated.value)+Number(!localOnly.value&&receiptStatus.value!=='active'))
function correctReceipt(action:'correct_receipt'|'void_receipt',rows:SampleIdentity[]){if(!canWrite.value)return;receiptRows.value=rows;receiptAction.value=action}
async function receiptSaved(ids:number[]){receiptAction.value=undefined;for(const id of ids)selected.value.delete(id);await load();if(detail.value)await loadDetail();ElMessage.success(uiText('收样登记已更新，原始记录和附件保留'))}
const suppliers=ref<SampleSupplierOption[]>([]),supplierCode=ref(String(route.query.supplierCode||'')),site=ref<SampleLocationSite|''>(localOnly.value?'MX':''),locationId=ref<number>(),chosenLocation=ref<SampleLocationOption>(),unlocated=ref(false)
const selecting=ref(false),locationRows=ref<SampleIdentity[]>([]),locationSetOpen=ref(false),locationManagerOpen=ref(false)
const selectionError=ref(''),exportProgress=ref(0)
let selectionEpoch=0
function clearSelection(){
 ++selectionEpoch;selecting.value=false;selected.value.clear();selectionError.value='';exportError.value=''
}
function selectPage(checked:boolean){
 for(const sample of data.value?.content || [])check(sample,checked)
}
async function selectResults(){
 if(selecting.value)return
 const ticket=++selectionEpoch
 selecting.value=true;selectionError.value=''
 try{
  const result=await querySampleIdentities({filters:filters()})
  if(ticket===selectionEpoch)selected.value=new Map(result.content.map(sample=>[sample.sampleIdentityId,sample]))
 }catch(e){if(ticket===selectionEpoch)selectionError.value=e instanceof Error?e.message:uiText('读取样品失败，请重试')}
 finally{if(ticket===selectionEpoch)selecting.value=false}
}
function setLocations(rows:SampleIdentity[]){locationRows.value=rows;locationSetOpen.value=true}
async function locationsSaved(){locationSetOpen.value=false;await bulkSaved()}
function moveLocal(row:SampleIdentity){bulkRows.value=[row];bulkAction.value='move'}
function beginBulk(action:'loan'|'return'|'move'){bulkRows.value=[...selected.value.values()];bulkAction.value=action}
function sendSelected(){void router.push({path:route.path,query:{view:'shipments',newShipment:'mexico_outbound',sampleIdentityIds:[...selected.value.keys()].join(',')}})}
async function bulkSaved(){bulkAction.value=undefined;selected.value=new Map();await load();if(detail.value)await loadDetail()}
async function colorSaved(value:SampleIdentity){selected.value.delete(Number(route.query.identityId));colorEditing.value=false;detail.value=value;await load();if(value.sampleIdentityId!==Number(route.query.identityId))void router.replace({path:route.path,query:{...route.query,identityId:value.sampleIdentityId}})}
const exporting=ref(false),exportError=ref(''),detailExportId=ref<number>(),exportTotal=ref(0)
let epoch=0,detailEpoch=0,exportEpoch=0,disposed=false
const canWrite=computed(()=>!localOnly.value&&user.hasPermission('samples:write'))
const actionsDisabled=computed(()=>!selected.value.size||selecting.value||loading.value)
const writesDisabled=computed(()=>actionsDisabled.value||[...selected.value.values()].some(row=>row.ledgerReadOnly||row.receiptStatus==='voided'))
const detailWritable=computed(()=>detail.value&&!detail.value.ledgerReadOnly&&detail.value.receiptStatus!=='voided')
const canCorrectColor=computed(()=>canWrite.value&&(user.permissions.includes('*')||user.profile?.canMaintainEngineeringData===true))
const scopes=[{key:'partner',label:'合作产品'},{key:'research',label:'新品研发'},{key:'model',label:'型号研发'}] as const
const receiptLabel=computed(()=>domain.value==='model'?'登记型号收样':domain.value==='research'?'登记研发收样':'其他来源收样')
const correctionColor=(data:Record<string,unknown>|undefined,key:string)=>String((data?.[key] as {colorName?:string})?.colorName||'未填写')
const quantity=(value:number|undefined)=>value==null?'—':new Intl.NumberFormat('zh-CN').format(value)
const positions=(row:SampleIdentity)=>(row.positions||row.balances.positions||[]).filter(p=>p.onhand>0&&(!localOnly.value||p.site!=='CN'))

function filters(){return {view:"ledger",domain:domain.value||undefined,keyword:appliedKeyword.value||undefined,supplierCode:supplierCode.value||undefined,site:localOnly.value?'MX':site.value||undefined,locationId:locationId.value,unlocated:unlocated.value||undefined,receiptStatus:receiptStatus.value}}
async function load(){
 const ticket=++epoch
 if(legacy.value||mode.value!=='identities'||!user.token){loading.value=false;return}
 loading.value=true;error.value=''
 try{
  const next=await sampleIdentities({...filters(),page:page.value,size:pageSize.value})
  if(ticket!==epoch)return
  if(page.value>Math.max(1,next.totalPages)){page.value=Math.max(1,next.totalPages);await load();return}
  const resetScroll=!data.value||data.value.page!==next.page||data.value.size!==next.size
  data.value=next
  for(const row of next.content)if(selected.value.has(row.sampleIdentityId))selected.value.set(row.sampleIdentityId,row)
  if(resetScroll){await nextTick();if(ticket===epoch)ledgerTable.value?.scrollToTop()}
 }catch(e){
  if(ticket===epoch){data.value=undefined;error.value=e instanceof Error?e.message:uiText('读取样品失败，请重试')}
 }finally{if(ticket===epoch)loading.value=false}
}
function goPage(value:number){if(value!==page.value){page.value=value;void load()}}
const pageSizeKey=computed(()=>user.profile?.userId ? 'plm.sampleLedger.pageSize.'+user.profile.userId : '')
function validSize(value:number){return Number.isInteger(value)&&value>=1&&value<=2000}
watch(pageSizeKey,key=>{
 let stored=0
 try{if(key)stored=Number(localStorage.getItem(key))}catch{/* Storage can be unavailable in private sessions. */}
 pageSize.value=validSize(stored)?stored:50
 customSizeOpen.value=!pageSizes.includes(pageSize.value)
 sizeDraft.value=String(pageSize.value);sizeError.value=false
},{immediate:true,flush:'sync'})
function applyPageSize(value:number){
 sizeError.value=!validSize(value)
 if(sizeError.value)return
 if(pageSizeKey.value)try{localStorage.setItem(pageSizeKey.value,String(value))}catch{/* The current session still uses the chosen size. */}
 if(value===pageSize.value)return
 pageSize.value=value;page.value=1;void load()
}
function choosePageSize(event:Event){
 const value=(event.target as HTMLSelectElement).value
 customSizeOpen.value=value==='custom';sizeError.value=false
 if(customSizeOpen.value)sizeDraft.value=String(pageSize.value)
 else applyPageSize(Number(value))
}
const firstRow=computed(()=>data.value?.content.length?((data.value.page||page.value)-1)*(data.value.size||pageSize.value)+1:0)
let searchTimer:ReturnType<typeof setTimeout>
function query(){clearTimeout(searchTimer);appliedKeyword.value=keyword.value.trim()}
function scheduleSearch(){clearTimeout(searchTimer);searchTimer=setTimeout(query,250)}
const supplierSearch=ref('')
const filteredSuppliers=computed(()=>{
 const term=supplierSearch.value.trim().toLocaleLowerCase()
 return term?suppliers.value.filter(s=>[s.supplierProfileShortName,s.supplierName,s.shortName,s.supplierCode].some(value=>value?.toLocaleLowerCase().includes(term))):suppliers.value
})
function filterSuppliers(term:string){supplierSearch.value=term}
function supplierDropdown(open:boolean){
 supplierSearch.value=''
 if(open)void loadSuppliers()
}
const optionsError=ref(''),supplierOptionsReady=ref(false),loadingSuppliers=ref(false)
let optionsEpoch=0
async function loadSuppliers(){
 if(loadingSuppliers.value||supplierOptionsReady.value||!user.token)return
 const ticket=++optionsEpoch
 optionsError.value='';loadingSuppliers.value=true
 try{const result=await sampleIdentityOptions(domain.value||undefined);if(ticket!==optionsEpoch)return;suppliers.value=result.suppliers;supplierOptionsReady.value=true}
 catch(e){if(ticket===optionsEpoch)optionsError.value=e instanceof Error?e.message:uiText('读取供应商失败，请重试')}
 finally{if(ticket===optionsEpoch)loadingSuppliers.value=false}
}
watch([domain,()=>user.token,()=>user.profile?.userId],()=>{
 ++optionsEpoch;supplierSearch.value='';suppliers.value=[];supplierOptionsReady.value=false;loadingSuppliers.value=false;optionsError.value=''
},{flush:'sync'})
watch([filtersOpen,site,domain,supplierCode,legacy,mode,()=>user.token,()=>user.profile?.userId],()=>{
 if(!filtersOpen.value||legacy.value||mode.value!=='identities'||!user.token)return
 searchLocations()
 if(supplierCode.value)void loadSuppliers()
},{immediate:true})
watch(site,()=>{locationId.value=undefined},{flush:'sync'})
watch(locationId,id=>{
 if(id)unlocated.value=false
 chosenLocation.value=id?locationOptions.rows.value.find(row=>row.id===id)||chosenLocation.value:undefined
},{flush:'sync'})
watch(unlocated,onlyUnset=>{if(onlyUnset)locationId.value=undefined},{flush:'sync'})
function searchLocations(keyword=''){void locationOptions.load({...(site.value?{site:site.value}:{}),...(keyword.trim()?{keyword:keyword.trim()}:{})})}
function retryOptions(){if(optionsError.value)void loadSuppliers();if(locationOptions.error.value)searchLocations()}
const detailLoading=ref(false),detailError=ref('')
async function loadDetail(){
 const id=Number(route.query.identityId),ticket=++detailEpoch
 detailError.value=''
 if(!id||legacy.value||!user.token){detail.value=undefined;detailLoading.value=false;return}
 detailLoading.value=true
 try{const value=await sampleIdentity(id,true);if(ticket===detailEpoch)detail.value=value}
 catch(e){if(ticket===detailEpoch){detail.value=undefined;detailError.value=e instanceof Error?e.message:uiText('读取样品详情失败')}}
 finally{if(ticket===detailEpoch)detailLoading.value=false}
}
function showView(view:string){void router.push({path:route.path,query:{...(domain.value&&!route.meta.sampleDomain?{domain:domain.value}:{}),...(view==='identities'?{}:{view})}})}
function open(row:SampleIdentity){void router.push({path:route.path,query:{...route.query,identityId:row.sampleIdentityId}})}
function check(row:SampleIdentity,checked:boolean){if(checked){selected.value.set(row.sampleIdentityId,row)}else selected.value.delete(row.sampleIdentityId)}
function labelRows(rows:SampleIdentity[]):SampleLabel[]{return rows.map(row=>({sampleIdentityId:row.sampleIdentityId,sampleCode:row.sampleCode,name:row.name,supplierName:row.supplierName,productName:row.productName,phoneName:row.phoneName,specName:row.specName,colorName:row.colorName,sampleVersion:row.sampleVersion}))}
function print(rows:SampleIdentity[]){labels.value=labelRows(rows);printOpen.value=true}
async function exportLabels(sample?:SampleIdentity){
 const rows=sample?[sample]:[...selected.value.values()]
 if(disposed||exporting.value||(!sample&&(loading.value||selecting.value))||!rows.length||!user.token)return
 detailExportId.value=sample?.sampleIdentityId;exportTotal.value=rows.length
 const ticket=++exportEpoch,auth=user.token,actor=user.profile?.userId,snapshot=labelRows(rows),origin=window.location.origin,now=new Date()
 const current=()=>!disposed&&ticket===exportEpoch&&auth===user.token&&actor===user.profile?.userId
 const fileName=`样品标签-${now.getFullYear()}${String(now.getMonth()+1).padStart(2,'0')}${String(now.getDate()).padStart(2,'0')}.xlsx`
 exporting.value=true;exportError.value='';exportProgress.value=0
 try{
  const {buildSampleLabelWorkbook}=await import('@/utils/sampleLabelExport')
  if(!current())return
  const blob=await buildSampleLabelWorkbook(snapshot,origin,(done)=>{if(current())exportProgress.value=done},current)
  if(current())saveBlob(blob,fileName)
 }catch(e){if(current())exportError.value=e instanceof Error?e.message:'标签导出失败，请重试'}finally{if(ticket===exportEpoch)exporting.value=false}
}
function openBatch(id:number,action=''){batchChoice.value=false;void router.push({path:route.path,query:{view:'batches',id,from:route.fullPath,...(action&&action!=='files'?{action}:{})}})}
function operate(action:string){if(!detail.value)return;pendingAction.value=action;if(detail.value.batches?.length===1)openBatch(detail.value.batches[0]!.id,action);else batchChoice.value=true}
function closeDetail(){const query={...route.query};delete query.identityId;void router.push({path:route.path,query})}
watch([()=>route.path,()=>route.query.domain,()=>route.query.supplierCode],()=>{
 clearTimeout(searchTimer);keyword.value='';appliedKeyword.value=''
 filtersOpen.value=true;sizeError.value=false;supplierCode.value=String(route.query.supplierCode||'')
 site.value=localOnly.value?'MX':'';locationId.value=undefined;unlocated.value=false;receiptStatus.value='active'
},{flush:'sync'})
watch(mode,value=>{if(value==='identities')filtersOpen.value=true})
watch([()=>route.path,domain,legacy,mode,localOnly,appliedKeyword,supplierCode,site,locationId,unlocated,receiptStatus,()=>user.token,()=>user.profile?.userId],()=>{
 clearSelection();data.value=undefined;page.value=1;void load()
},{immediate:true})
watch([()=>route.path,()=>route.query.identityId,legacy,()=>user.token,()=>user.profile?.userId],()=>{
 detail.value=undefined;void loadDetail()
},{immediate:true})
watch([()=>user.token,()=>user.profile?.userId],()=>{
 ++epoch;++detailEpoch;++exportEpoch;clearSelection()
 data.value=undefined;detail.value=undefined;filtersOpen.value=true
 receiptAction.value=undefined;bulkAction.value=undefined;colorEditing.value=false;locationSetOpen.value=false;locationManagerOpen.value=false
 labels.value=[];printOpen.value=false;batchChoice.value=false;exporting.value=false;exportError.value=''
 site.value=localOnly.value?'MX':'';locationId.value=undefined
},{flush:'sync'})
onBeforeUnmount(()=>{disposed=true;clearTimeout(searchTimer);++epoch;++detailEpoch;++selectionEpoch;++exportEpoch;++optionsEpoch})
</script>
<template>
 <PartnerWorkspace v-if="legacy"/>
 <section v-else class="sample-management">
  <header class="sample-title">
    <h1>{{uiText('样品管理')}}</h1>
    <nav v-if="!localOnly&&user.hasPermission('partner:view')" class="scope-switch" :aria-label="uiText('业务范围')">
      <button v-for="scope in scopes" :key="scope.key" :aria-pressed="domain===scope.key" @click="router.push({path:sampleRoutes[scope.key],query:mode==='shipments'?{view:'shipments'}:{}})">{{uiText(scope.label)}}</button>
    </nav>
  </header>
  <div class="ledger-nav">
    <nav class="view-switch" :aria-label="uiText('样品管理')">
      <button :aria-pressed="mode==='identities'" @click="showView('identities')">{{uiText('样品台账')}}</button>
      <button v-if="!localOnly||access.canWrite" :aria-pressed="mode==='shipments'" @click="showView('shipments')">{{uiText(localOnly?'本人待签收':'寄样物流')}}</button>
    </nav>
    <div v-if="mode==='identities'" class="header-actions">
      <el-button v-if="access.canWrite" :icon="Location" size="small" :aria-label="uiText('位置管理')" @click="locationManagerOpen=true"><span class="location-label">{{uiText('位置管理')}}</span></el-button>
      <el-button v-if="canWrite&&!['model','research'].includes(domain)" :icon="Plus" type="primary" size="small" :title="uiText('从选品目录登记收样')" @click="router.push({path:'/partner-selections',query:{from:route.fullPath}})">{{uiText(domain==='partner'?'登记收样':'从选品目录登记收样')}}</el-button>
      <el-button v-if="canWrite&&domain!=='partner'" :icon="Plus" type="primary" size="small" :title="uiText(receiptLabel)" @click="router.push({path:route.path,query:{view:'batches',new:'1',from:route.fullPath}})">{{uiText(domain?'登记收样':receiptLabel)}}</el-button>
    </div>
  </div>
  <SampleShipments :key="domain" v-if="mode==='shipments'" :domain="domain"/>
  <section v-else class="ledger-panel">
    <div class="ledger-tools">
      <el-input v-model="keyword" :prefix-icon="Search" clearable :aria-label="uiText('搜索样品码、款式、型号或颜色')" :placeholder="uiText('搜索样品码、款式、型号或颜色')" @input="scheduleSearch" @keyup.enter="query" @clear="query"/>
      <el-button :icon="Filter" plain :aria-expanded="filtersOpen" aria-controls="ledger-filters" @click="filtersOpen=!filtersOpen">{{uiText(filtersOpen?'收起筛选':'展开筛选')}}<template v-if="filterCount"> · {{filterCount}}</template></el-button>
      <div v-if="data" class="ledger-count">
        <span aria-live="polite">{{uiText('已选 {selected} / {total} 种',{selected:selected.size,total:data.totalElements})}}</span>
        <el-button v-if="selected.size&&selected.size<data.totalElements" link :disabled="selecting||loading" @click="selectResults">{{uiText('全选当前筛选结果（{count}种）',{count:data.totalElements})}}</el-button>
        <el-button v-if="selected.size" link :disabled="selecting" @click="clearSelection">{{uiText('清空已选')}}</el-button>
      </div>
    </div>
    <div v-if="filtersOpen" id="ledger-filters" class="filter-panel">
      <label class="filter-field"><span>{{uiText('供应商')}}</span><el-select v-model="supplierCode" clearable filterable :loading="loadingSuppliers" :placeholder="uiText('全部供应商')" :aria-label="uiText('供应商筛选')" :filter-method="filterSuppliers" @visible-change="supplierDropdown">
        <el-option v-for="s in filteredSuppliers" :key="s.supplierCode" :value="s.supplierCode" :label="s.supplierProfileShortName||s.supplierName||s.supplierCode" :title="s.supplierName"/>
      </el-select></label>
      <label v-if="!localOnly" class="filter-field"><span>{{uiText('站点')}}</span><el-select v-model="site" clearable :placeholder="uiText('全部站点')" :aria-label="uiText('站点筛选')">
        <el-option v-for="s in sampleLocationSites" :key="s.value" :value="s.value" :label="uiText(s.label)"/>
      </el-select></label>
      <label class="filter-field"><span>{{uiText('库位')}}</span><el-select v-model="locationId" filterable remote clearable :remote-method="searchLocations" :loading="locationOptions.loading.value" :placeholder="uiText('全部库位')" :aria-label="uiText('库位筛选')">
        <el-option v-if="chosenLocation&&!locationOptions.rows.value.some(r=>r.id===chosenLocation?.id)" :value="chosenLocation.id" :label="sampleLocationLabel(chosenLocation)"/>
        <el-option v-for="location in locationOptions.rows.value" :key="location.id" :value="location.id" :label="sampleLocationLabel(location)"/>
      </el-select></label>
      <label v-if="!localOnly" class="filter-field"><span>{{uiText('登记状态')}}</span><el-select v-model="receiptStatus" :aria-label="uiText('收样登记状态')">
        <el-option :label="uiText('有效登记')" value="active"/><el-option :label="uiText('已删除登记')" value="voided"/><el-option :label="uiText('全部登记')" value="all"/>
      </el-select></label>
      <el-checkbox v-model="unlocated">{{uiText('未设库位')}}</el-checkbox>
      <el-button size="small" @click="supplierCode='';site=localOnly?'MX':'';locationId=undefined;unlocated=false;receiptStatus='active'">{{uiText('重置')}}</el-button>
      <el-button size="small" @click="load">{{uiText('刷新')}}</el-button>
      <el-alert v-if="optionsError||locationOptions.error.value" :title="optionsError||locationOptions.error.value" type="error" :closable="false"><el-button link @click="retryOptions">{{uiText('重试')}}</el-button></el-alert>
    </div>
    <div class="selection-actions" role="group" :aria-label="uiText('批量操作')">
      <strong>{{uiText('批量操作')}}</strong>
      <div v-if="access.canWrite" class="action-group">
        <el-button :disabled="writesDisabled" size="small" @click="setLocations([...selected.values()])">{{uiText('批量设置位置')}}</el-button>
        <el-button v-if="canWrite" :disabled="writesDisabled" size="small" @click="beginBulk('move')">{{uiText('精细移库')}}</el-button>
      </div>
      <div v-if="canWrite" class="action-group">
        <el-button :disabled="writesDisabled" size="small" @click="sendSelected">{{uiText('发往墨西哥')}}</el-button>
        <el-button :disabled="writesDisabled" size="small" @click="beginBulk('loan')">{{uiText('借出')}}</el-button>
        <el-button :disabled="writesDisabled" size="small" @click="beginBulk('return')">{{uiText('归还')}}</el-button>
      </div>
      <div class="action-group">
        <el-button :disabled="actionsDisabled" size="small" @click="print([...selected.values()])">{{uiText('打印标签')}}</el-button>
        <el-button :disabled="actionsDisabled||exporting" size="small" @click="exportLabels()">{{uiText('导出标签')}}</el-button>
      </div>
      <div v-if="canWrite" class="action-group">
        <el-button :disabled="writesDisabled" size="small" class="danger" @click="correctReceipt('void_receipt',[...selected.values()])">{{uiText('删除登记')}}</el-button>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load">{{uiText('重试')}}</el-button></el-alert>
    <el-alert v-if="exportError&&!detailExportId" :title="exportError" type="error" :closable="false"><el-button link :disabled="selecting" @click="exportLabels()">{{uiText('重试导出')}}</el-button></el-alert>
    <el-alert v-if="selectionError" :title="selectionError" type="error" :closable="false"><el-button link @click="selectResults">{{uiText('重试')}}</el-button></el-alert>
    <p v-if="selecting||(exporting&&!detailExportId)" role="status">{{selecting?uiText('正在选择样品…'):uiText('正在生成标签 {done}/{total}',{done:exportProgress,total:exportTotal})}}</p>
    <SampleLedgerTable ref="ledgerTable" v-loading="loading" :busy="loading||selecting" :local-only="localOnly" :site="site||chosenLocation?.site" :rows="data?.content||[]" :selected="selected" @check-page="selectPage" @check="check" @open="open"/>
    <footer>
      <span class="page-range" aria-live="polite" v-if="data">{{data.content.length?firstRow+'–'+(firstRow+data.content.length-1)+' / ':''}}{{uiText('{count} 种样品',{count:data.totalElements})}}</span>
      <div class="page-controls">
        <label>{{uiText('每页')}} <select :aria-label="uiText('每页样品数')" :value="customSizeOpen?'custom':String(pageSize)" @change="choosePageSize">
          <option v-for="size in pageSizes" :key="size" :value="size">{{size}} {{uiText('条')}}</option><option value="custom">{{uiText('自定义…')}}</option>
        </select></label>
        <form v-if="customSizeOpen" novalidate @submit.prevent="applyPageSize(Number(sizeDraft))">
          <input v-model="sizeDraft" type="number" min="1" max="2000" step="1" inputmode="numeric" placeholder="1–2000" :aria-label="uiText('自定义每页条数')" :aria-invalid="sizeError" @input="sizeError=false">
          <el-button size="small" native-type="submit">{{uiText('应用')}}</el-button>
        </form>
        <el-pagination v-if="data" :current-page="page" :page-size="pageSize" :total="data.totalElements" layout="prev,slot,next" size="small" @current-change="goPage"><span>{{page}} / {{Math.max(1,data.totalPages||1)}}</span></el-pagination>
      </div>
      <span v-if="sizeError" class="size-error" role="alert">{{uiText('请输入 1 至 2000 的整数')}}</span>
    </footer>
  </section>
  <el-drawer :model-value="!!route.query.identityId" :title="uiText('样品详情')" size="min(480px,100vw)" @close="closeDetail">
    <div v-loading="detailLoading" class="identity-detail">
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false"><el-button link @click="loadDetail">{{uiText('重试')}}</el-button></el-alert>
      <template v-if="detail">
        <h2>{{detail.productName||detail.name}}</h2><span class="detail-code">{{detail.sampleCode}}</span>
        <dl><dt>{{uiText('适用型号')}}</dt><dd>{{detail.phoneName||detail.specName||'—'}}</dd><dt>{{uiText('颜色')}}</dt><dd>{{[detail.colorName,detail.sampleVersion].filter(Boolean).join(' · ')||'—'}}</dd><dt>{{uiText('供应商')}}</dt><dd :title="detail.supplierName">{{detail.supplierProfileShortName||detail.supplierName||detail.supplierCode||uiText('待核对')}}</dd></dl>
        <h3>{{uiText('库存与位置')}}</h3>
        <table class="positions"><thead><tr><th>{{uiText('站点')}}</th><th>{{uiText('库位')}}</th><th>{{uiText('在库')}}</th></tr></thead>
          <tbody><tr v-for="p in positions(detail)" :key="samplePositionKey(p)"><td>{{p.site==='CN'?uiText('中国'):p.site==='MX'?uiText('墨西哥'):'—'}}</td><td>{{sampleLocationLabel(p)}}</td><td>{{quantity(p.onhand)}}</td></tr></tbody>
        </table>
        <p v-if="!positions(detail).length">{{uiText('当前没有在库实物。')}}</p>
        <div class="identity-summary"><span>{{uiText('可用 ')}}{{detail.balances.available}}</span><span>{{uiText('借出 ')}}{{detail.balances.borrowed}}</span><span>{{uiText('在途 ')}}{{quantity(detail.balances.inTransit)}}</span></div>
        <div class="detail-actions">
          <el-button v-if="access.canWrite&&detailWritable" type="primary" size="small" :disabled="!detail.balances.onhand" @click="setLocations([detail])">{{uiText('设置位置')}}</el-button>
          <div class="commands">
            <button @click="print([detail])">{{uiText('打印／补打二维码标签')}}</button>
            <button :disabled="exporting" @click="exportLabels(detail)">{{uiText('导出标签')}}</button>
            <button v-if="localOnly&&access.canWrite&&detailWritable" @click="moveLocal(detail)">{{uiText('精细移库')}}</button>
            <template v-if="canWrite&&detailWritable">
              <button @click="correctReceipt('correct_receipt',[detail])">{{uiText('改数量')}}</button>
              <button v-if="detail.sourceType==='catalog'&&canCorrectColor" @click="colorEditing=true">{{uiText('更正颜色')}}</button>
              <button @click="operate('loan')">{{uiText('借出')}}</button><button @click="operate('return')">{{uiText('归还')}}</button>
              <button @click="operate('move')">{{uiText('移动位置')}}</button>
              <button @click="router.push({path:route.path,query:{view:'shipments',newShipment:'mexico_outbound',sampleIdentityId:detail.sampleIdentityId}})">{{uiText('发往墨西哥')}}</button>
              <button class="danger" @click="correctReceipt('void_receipt',[detail])">{{uiText('删除登记')}}</button>
            </template>
          </div>
        </div>
        <el-alert v-if="exportError&&detailExportId===detail.sampleIdentityId" :title="exportError" type="error" :closable="false"><el-button link @click="exportLabels(detail)">{{uiText('重试导出')}}</el-button></el-alert>
        <details v-if="positions(detail).length"><summary>{{uiText('数量明细')}}</summary>
          <p v-for="p in positions(detail)" :key="samplePositionKey(p)">{{sampleLocationLabel(p)}} · {{uiText('可用 ')}}{{p.available}} · {{uiText('留样')}} {{p.retained}} · {{uiText('损坏')}} {{p.damaged}}</p>
        </details>
        <details v-if="!localOnly&&!detail.ledgerReadOnly"><summary>{{uiText('样品资料')}}</summary>
          <p v-if="detail.legacySampleCode">{{uiText('旧码 ')}}{{detail.legacySampleCode}}</p>
          <CatalogCurrentInfo :value="detail.currentCatalog" :recorded-name="detail.recordedName"/>
          <PartnerFiles kind="sample-identities" :id="detail.sampleIdentityId" :files="detail.files||[]" :model-value="[]" :can-write="false" :can-write-money="false" compact/>
          <el-button v-if="canWrite&&detailWritable" size="small" @click="operate('files')">{{uiText('上传到原收样批次')}}</el-button>
        </details>
<h3 v-if="(detail.loans||detail.balances.loans||[]).some(l=>l.outstanding>0)">{{ uiText("借出待还") }}</h3><p v-for="loan in (detail.loans||detail.balances.loans||[]).filter(l=>l.outstanding>0)" :key="loan.id">{{loan.person}}{{ uiText(" · 待还 ") }}{{loan.outstanding}}{{ uiText(" · 预计 ") }}{{loan.due||uiText('未记录')}}</p><template v-if="!localOnly&&detail.events?.some(e=>e.action==='sample_color_correction')"><h3>{{ uiText("颜色更正记录") }}</h3><p v-for="event in detail.events.filter(e=>e.action==='sample_color_correction')" :key="event.id">{{correctionColor(event.data,'before')}} → {{correctionColor(event.data,'after')}} · {{event.operator}}<br/>{{event.data?.reason}}</p></template><details v-if="!localOnly&&!detail.ledgerReadOnly&&detail.batches?.length"><summary>{{ uiText("收样批次与原流转") }}</summary><article v-for="batch in (!localOnly?detail.batches:[])||[]" :key="batch.id" class="batch-row"><span>{{batch.receivedDate||uiText('日期待核对')}}{{ uiText(" · 实收 ") }}{{quantity(batch.effectiveReceivedQty??batch.receivedQty)}}<template v-if="batch.receiptStatus==='voided'"> · {{uiText('已删除登记，原记录保留')}}</template>{{ uiText(" · 在库 ") }}{{batch.balances.onhand}}{{ uiText(" · 借出 ") }}{{batch.balances.borrowed}}<small>{{ uiText("内部批次 ") }}{{batch.code}}</small></span><el-button size="small" @click="openBatch(batch.id)">{{ uiText("原记录／图片附件／更正") }}</el-button></article></details></template></div></el-drawer>
  <el-dialog v-model="batchChoice" :title="pendingAction==='files'?uiText('选择图片附件归属的原收样批次'):uiText('选择本次办理的原收样批次')" width="min(620px,96vw)" append-to-body><p>{{ uiText("同一码可多次收样，借还和移位继续使用原批次数量。") }}</p><div v-for="batch in detail?.batches||[]" :key="batch.id" class="batch-row"><span>{{batch.receivedDate}}{{ uiText(" · 在库 ") }}{{batch.balances.onhand}}{{ uiText(" · 可借 ") }}{{batch.balances.available}}<small>{{ uiText("内部 ") }}{{batch.code}}</small></span><el-button type="primary" size="small" @click="openBatch(batch.id,pendingAction)">{{pendingAction==='files'?uiText('进入本批附件'):uiText('办理本批')}}</el-button></div></el-dialog>
  <SampleLocationSetDialog v-if="locationSetOpen" :samples="locationRows" @close="locationSetOpen=false" @saved="locationsSaved"/><SampleLocationManager v-if="locationManagerOpen" @close="locationManagerOpen=false"/><SampleBatchActionDialog v-if="bulkAction" :action="bulkAction" :samples="bulkRows" @close="bulkAction=undefined" @saved="bulkSaved"/><SampleColorCorrectionDialog v-if="colorEditing&&detail" :sample="detail" @close="colorEditing=false" @saved="colorSaved"/>
  <SampleReceiptCorrectionDialog v-if="receiptAction&&canWrite" :action="receiptAction" :samples="receiptRows" @close="receiptAction=undefined" @saved="receiptSaved" @inspect="receiptAction=undefined;openBatch($event)"/><SampleLabelPrint v-model="printOpen" :samples="labels"/>
 </section>
</template>
<style scoped>
.sample-management{height:100%;min-height:0;min-width:0;display:flex;flex-direction:column;container-type:inline-size;color:#25394f}
.sample-title,.ledger-nav,.header-actions,.view-switch,.scope-switch,.ledger-tools,.filter-panel,.selection-actions,.detail-actions,.identity-summary{display:flex;align-items:center;gap:9px;flex-wrap:wrap}
.sample-title{gap:18px;padding:7px 0 11px;border-bottom:1px solid #dce4ed}.sample-title h1{margin:0;font-size:17px}
.scope-switch{gap:2px;padding:3px;border-radius:6px;background:#e8edf3}.scope-switch button,.view-switch button{background:none;border:0;cursor:pointer;color:#53657a}
.scope-switch button{padding:4px 10px;border-radius:4px;font-size:12px}.scope-switch button[aria-pressed=true]{color:#233c59;background:#fff;box-shadow:0 1px 2px #1c345622}
.ledger-nav{justify-content:space-between;padding:12px 0}.view-switch{gap:18px}.view-switch button{font-size:15px;font-weight:600;padding:4px 0 7px;border-bottom:2px solid transparent}.view-switch button[aria-pressed=true]{color:#24445e;border-bottom-color:#2876dc}
.header-actions :deep(.el-button),.selection-actions :deep(.el-button){margin:0}
.ledger-panel{display:flex;flex-direction:column;min-height:0;flex:1;border:1px solid #dfe6ef;border-radius:8px;background:#fff}
.ledger-tools,.filter-panel,.selection-actions{padding:10px 12px;border-bottom:1px solid #e6ebf2}.ledger-tools>.el-input{width:min(330px,55%)}.ledger-count{margin-inline-start:auto;font-size:12px;color:#586c81}
.filter-panel{background:#f7f9fc}.filter-field{display:grid;gap:3px;min-width:0;width:min(180px,100%);font-size:12px;color:#52657b}
.ledger-count,.action-group,.commands,.page-controls,.page-controls form{display:flex;align-items:center;flex-wrap:wrap;gap:6px}
.ledger-count :deep(.el-button){margin:0}.selection-actions{font-size:13px;background:white}
.action-group+.action-group{border-inline-start:1px solid #dce4ed;padding-inline-start:9px}
.selection-actions :deep(.el-button.is-disabled){color:#69788a;background:#f7f9fb;border-color:#dde4ec}
.danger:not(:disabled){color:#ab383b}
.ledger-panel footer{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:5px 12px;padding:6px 10px;border-top:1px solid #e2e9f1;color:#586c81;font-size:12px;font-variant-numeric:tabular-nums}
.page-controls{margin-inline-start:auto}.page-controls select,.page-controls input{font:inherit;background:#fff;border:1px solid #d7dfe8;border-radius:4px;color:#29435e;height:28px;padding:2px 5px}.page-controls input{width:76px}
.page-controls :deep(.el-pagination){gap:7px}.size-error{width:100%;text-align:end;color:#ab383b}
.commands button{border:1px solid #d7dfe8;border-radius:4px;padding:4px 8px;background:white;color:#29435e;cursor:pointer;font:inherit}.commands button:hover{background:#eef5ff}
.identity-detail{font-size:13px;color:#293c52}.identity-detail h2{font-size:20px;margin:0}.detail-code{font:13px ui-monospace,monospace;color:#5d7189}.identity-detail dl{display:grid;grid-template-columns:auto 1fr;gap:8px 16px;margin:16px 0}.identity-detail dt{color:#61738a}.identity-detail dd{margin:0;min-width:0;overflow-wrap:anywhere}
.identity-detail h3{margin:16px 0 6px;font-size:14px}.positions{width:100%;border-collapse:collapse;text-align:start}.positions th,.positions td{border-bottom:1px solid #e4eaf0;padding:6px 8px;text-align:start}.positions th{color:#5a6c82;background:#f5f8fb}.positions th:last-child,.positions td:last-child{text-align:end}
.identity-summary{font-size:12px;color:#5d7187;margin:8px 0 14px}.detail-actions{margin-bottom:16px}.identity-detail>details{border-top:1px solid #e3e9ef;padding:9px 0}.identity-detail>details>summary{font-weight:600;cursor:pointer}
.batch-row{display:flex;justify-content:space-between;align-items:center;gap:8px;padding:7px 0;border-bottom:1px solid #e5ebf2}.batch-row small{display:block;color:#62748b}
@container(max-width:660px){.sample-title{gap:7px}.scope-switch button{padding:5px 7px}.ledger-nav{gap:5px;padding:8px 0}.header-actions,.view-switch{gap:6px}.ledger-tools,.filter-panel,.selection-actions{padding:8px}.ledger-tools>.el-input{flex:1;min-width:120px}.filter-field{flex:1 1 130px}.location-label{display:none}.header-actions :deep(.el-button:has(.location-label) span:empty),.header-actions :deep(.el-button:has(.location-label) .el-icon+span){display:none}.action-group+.action-group{padding:0;border:0}}
@container(max-width:440px){.ledger-count{width:100%;margin:0}.selection-actions{gap:6px}.sample-management .ledger-panel{overflow:auto}.ledger-panel :deep(.ledger-scroll){min-height:165px}.page-controls{margin:0}}
@media(max-height:620px){.ledger-panel{overflow:auto}.ledger-panel :deep(.ledger-scroll){min-height:165px}.ledger-panel footer{position:sticky;bottom:0;z-index:2;background:#fff}}
</style>
