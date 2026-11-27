<script setup lang="ts">
import {computed,onMounted,onBeforeUnmount,reactive,ref,watch} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {Filter,Search} from '@element-plus/icons-vue'
import {t as uiText} from '@/locales'
import {getProductPage} from '@/api/modules/product'
import {confirmAutoMatchedMapping,previewAutoMatchedMapping,getEntityMappings,getMoldShareMappings,saveMoldShareMapping,type AutoMatchPreview,type EntityMappingRow,type MoldShareMapping,type MappingInput,type MappingQuery} from '@/api/modules/moldSharing'
import {getFinishedProductMappings,type FinishedProductMapping} from '@/api/modules/finishedProductMappings'
import type {HoleType} from '@/api/modules/moldApplications'
import type {ManufacturingRelation} from '@/api/modules/manufacturing'
import SharedCompatiblePhoneSelector from '@/views/mold/components/SharedCompatiblePhoneSelector.vue'
import ManufacturingRelationPicker from '@/views/mold/components/ManufacturingRelationPicker.vue'
import {holeTypeNames} from '@/utils/engineeringPlan'

const rows=ref<MoldShareMapping[]>([]),total=ref(0),page=ref(1),loading=ref(false),saving=ref(false),error=ref(''),saveError=ref(''),canWrite=ref(false)
const filters=reactive({keyword:'',status:'active',productLineId:undefined as number|undefined,sourcePhoneModelId:undefined as number|undefined,referencePhoneModelId:undefined as number|undefined,materialCode:'',holeType:'',structureDescription:''})
const mobileFiltersOpen=ref(false)
const activeFilterCount=computed(()=>Number(!!filters.holeType)+Number(filters.status!=='active'))
const products=ref<{productId:number;productName:string}[]>([]),productBusy=ref(false)
const entityRows=ref<EntityMappingRow[]>([]),entityTotal=ref(0),entityPage=ref(1),entityLoading=ref(false),entityListError=ref('')
const finishedProductRows=ref<FinishedProductMapping[]>([])
let loadEpoch=0,productEpoch=0
async function searchProducts(value:string){
  const epoch=++productEpoch;productBusy.value=true
  try{const result=await getProductPage({productType:'product_line',keyword:value,page:1,size:100});if(epoch===productEpoch)products.value=result.content}
  catch(e){if(epoch===productEpoch)ElMessage.error(e instanceof Error?e.message:uiText('产品读取失败'))}
  finally{if(epoch===productEpoch)productBusy.value=false}
}
async function load(){
  const epoch=++loadEpoch;loading.value=true;error.value=''
  const query:MappingQuery={page:page.value,size:20,status:filters.status}
  // Load complete groups before applying display filters and pagination.
  try{const result=await getMoldShareMappings({...query,page:1,size:100,status:'all'});const all=[...result.content];for(let next=2;all.length<result.totalElements;next++){const more=await getMoldShareMappings({...query,page:next,size:100,status:'all'});if(!more.content.length)break;all.push(...more.content)}if(epoch!==loadEpoch)return;rows.value=all;total.value=result.totalElements;canWrite.value=result.canWrite}
  catch(e){if(epoch!==loadEpoch)return;rows.value=[];total.value=0;canWrite.value=false;error.value=e instanceof Error?e.message:uiText('映射读取失败，请重试')}
  finally{if(epoch===loadEpoch)loading.value=false}
}
let entityListEpoch=0
async function loadEntityMappings(){const epoch=++entityListEpoch;entityLoading.value=true;entityListError.value='';try{const result=await getEntityMappings({page:1,size:100,status:'all'});const all=[...result.content];for(let next=2;all.length<result.totalElements;next++){const more=await getEntityMappings({page:next,size:100,status:'all'});if(!more.content.length)break;all.push(...more.content)}const finished=await getFinishedProductMappings({page:1,size:100,status:'all'});const finishedRows=[...finished.content];for(let next=2;finishedRows.length<finished.totalElements;next++){const more=await getFinishedProductMappings({page:next,size:100,status:'all'});if(!more.content.length)break;finishedRows.push(...more.content)}if(epoch!==entityListEpoch)return;entityRows.value=all;entityTotal.value=result.totalElements;finishedProductRows.value=finishedRows}catch(e){if(epoch!==entityListEpoch)return;entityRows.value=[];finishedProductRows.value=[];entityTotal.value=0;entityListError.value=e instanceof Error?e.message:uiText('实体映射读取失败')}finally{if(epoch===entityListEpoch)entityLoading.value=false}}
const groupedMappings=computed(()=>{
  const groups=new Map<string,{key:string;code:string;materialCode:string;holeType:string;phones:Map<number,string>;codes:Set<string>;finishedCodes:Set<string>;status:string}>()
  const add=(key:string,code:string,materialCode:string,holeType:string,status:string,phones:[number,string][],alias?:string)=>{let group=groups.get(key);if(!group){group={key,code,materialCode,holeType,status,phones:new Map(),codes:new Set(),finishedCodes:new Set()};groups.set(key,group)}for(const [id,name] of phones)if(id)group.phones.set(id,name);if(alias&&alias!==code)group.codes.add(alias)}
  for(const row of entityRows.value)add(`${row.productLineId}:${row.moldSetId}:${row.primaryCode}:${row.holeType}:${row.status}`,row.primaryCode,row.materialCode,row.holeType,row.status,[[row.targetPhoneModelId,`${row.targetPhoneName} · ${row.targetPhoneCode}`],[row.referencePhoneModelId||0,`${row.referencePhoneName||''} · ${row.referencePhoneCode||''}`]],row.mappingCode)
  for(const row of rows.value){const key=`${row.productLineId}:${row.moldSetId}:${row.moldCode}:${row.holeType}:${row.status}`;add(key,row.moldCode,row.materialCode,row.holeType,row.status,[[row.sourcePhoneModelId,`${row.sourcePhoneName} · ${row.sourcePhoneCode}`],[row.referencePhoneModelId,`${row.referencePhoneName} · ${row.referencePhoneCode}`]])}
  for(const mapping of finishedProductRows.value){for(const basis of mapping.moldMappings){const group=groups.get(`${mapping.productLineId}:${basis.moldSetId}:${basis.primaryCode}:${mapping.holeType}:${mapping.status}`);if(group&&mapping.skus.some(sku=>sku.phoneModelId===basis.phoneModelId))group.finishedCodes.add(mapping.mappingCode)}}
  const keyword=filters.keyword.trim().toLowerCase()
  return [...groups.values()].filter(row=>(filters.status==='all'||row.status===filters.status)&&(!filters.holeType||row.holeType===filters.holeType)&&(!keyword||[row.code,...row.codes,...row.finishedCodes,...row.phones.values()].join(' ').toLowerCase().includes(keyword)))
})
const visibleMappings=computed(()=>groupedMappings.value.slice((entityPage.value-1)*20,entityPage.value*20))
function query(){page.value=1;entityPage.value=1;void load();void loadEntityMappings()}
function reset(){Object.assign(filters,{keyword:'',status:'active',productLineId:undefined,sourcePhoneModelId:undefined,referencePhoneModelId:undefined,materialCode:'',holeType:'',structureDescription:''});query()}
const open=ref(false),selecting=ref(false),editingId=ref<number|null>(null),targetCode=ref(''),readonly=ref(false),source=ref<MoldShareMapping|null>(null)
const blank=():MappingInput=>({version:undefined,productLineId:0,sourcePhoneModelId:0,referencePhoneModelId:0,materialCode:'10',holeType:'precision',structureDescription:'',manufacturingRelationId:0,manufacturingRelationVersion:0,evidence:'',reason:'',status:'active'})
const form=reactive<MappingInput>(blank())
const entityDialog=ref(false),entitySaving=ref(false),entityError=ref('')
const entityForm=reactive({productLineId:0,referencePhoneModelId:0,targetPhoneModelId:0,holeType:'' as HoleType|'',materialCodes:['10','20'] as string[],selectedMoldSetId:undefined as number|undefined,evidence:'',reason:''})
const entityPreview=ref<AutoMatchPreview|null>(null),entityPreviewLoading=ref(false),entityPreviewError=ref('')
const sameEntityPhones=computed(()=>entityForm.referencePhoneModelId>0&&entityForm.referencePhoneModelId===entityForm.targetPhoneModelId)
let entityPreviewEpoch=0
function entityPayload(){return {productLineId:entityForm.productLineId,referencePhoneModelId:entityForm.referencePhoneModelId,targetPhoneModelId:entityForm.targetPhoneModelId,holeType:entityForm.holeType as HoleType,materialCodes:[...entityForm.materialCodes],selectedMoldSetId:entityForm.selectedMoldSetId,matchToken:entityPreview.value?.matchToken||'',evidence:entityForm.evidence.trim(),reason:entityForm.reason.trim()}}
function candidateLabel(row:AutoMatchPreview['candidates'][number]){const product=row.sourceProductLineName?`${row.sourceProductLineName} · `:'';const mold=row.moldName||uiText('实际共用模具');const set=row.setName||uiText('第 {value0} 套',{value0:row.setNo||'—'});return `${product}${mold} · ${set} · ${uiText('模具档案 #{value0}',{value0:row.itemId})} · ${uiText('实物套 #{value0}',{value0:row.moldSetId})}`}
function previewErrorMessage(error:unknown){const responseMessage=(error as {response?:{data?:{message?:unknown}}})?.response?.data?.message;return typeof responseMessage==='string'?uiText(responseMessage):error instanceof Error?error.message:uiText('自动匹配失败')}
async function loadEntityPreview(){const epoch=++entityPreviewEpoch;entityPreview.value=null;entityPreviewError.value='';entityPreviewLoading.value=false;if(!entityDialog.value)return;if(sameEntityPhones.value){entityPreviewError.value=uiText('已开模机型与新增适用机型不能相同');return}if(!entityForm.productLineId||!entityForm.referencePhoneModelId||!entityForm.holeType||!entityForm.targetPhoneModelId||!entityForm.materialCodes.length)return;entityPreviewLoading.value=true;try{const result=await previewAutoMatchedMapping(entityPayload());if(epoch===entityPreviewEpoch)entityPreview.value=result}catch(e){if(epoch===entityPreviewEpoch)entityPreviewError.value=previewErrorMessage(e)}finally{if(epoch===entityPreviewEpoch)entityPreviewLoading.value=false}}
watch(()=>[entityDialog.value,entityForm.productLineId,entityForm.referencePhoneModelId,entityForm.holeType,entityForm.targetPhoneModelId,entityForm.materialCodes.join(','),entityForm.selectedMoldSetId],()=>{void loadEntityPreview()})
let initial=''
function clearRelation(){form.manufacturingRelationId=0;form.manufacturingRelationVersion=0;targetCode.value=''}
function edit(row?:MoldShareMapping,viewOnly=false){
  editingId.value=row?.id??null;source.value=row??null;readonly.value=viewOnly;saveError.value=''
  Object.assign(form,blank(),row?{version:row.version,productLineId:row.productLineId,sourcePhoneModelId:row.sourcePhoneModelId,referencePhoneModelId:row.referencePhoneModelId,materialCode:row.materialCode,holeType:row.holeType,structureDescription:row.structureDescription,manufacturingRelationId:row.manufacturingRelationId,manufacturingRelationVersion:row.manufacturingRelationVersion,evidence:row.evidence,status:row.status}:{} )
  targetCode.value=row?.moldCode??''
  if(row&&!products.value.some(p=>p.productId===row.productLineId))products.value.push({productId:row.productLineId,productName:row.productLineName})
  initial=JSON.stringify(form);open.value=true
}
async function close(done?:()=>void){
  if(saving.value)return
  if(!readonly.value&&JSON.stringify(form)!==initial){try{await ElMessageBox.confirm(uiText('有未保存的修改，确定关闭？'),uiText('提示'),{type:'warning',confirmButtonText:uiText('关闭'),cancelButtonText:uiText('继续编辑')})}catch{return}}
  selecting.value=false;open.value=false;done?.()
}
function adopt(row:ManufacturingRelation){
  if(row.structureDescription.trim()!==form.structureDescription.trim()){ElMessage.warning(uiText('所选工程关系的结构说明与映射不一致，请重新核对'));return}
  const code=(row.parts||[]).filter(p=>p.partType===(form.materialCode==='10'?'TPU':'PC')).map(p=>p.code).join(' / ')
  if(!code){ElMessage.warning(uiText('所选实物套缺少该材质的原模具编码'));return}
  form.manufacturingRelationId=row.relationId||row.id;form.manufacturingRelationVersion=row.relationVersion||row.version;targetCode.value=code;selecting.value=false
}
function openEntityConfirmation(){Object.assign(entityForm,{productLineId:0,referencePhoneModelId:0,targetPhoneModelId:0,holeType:'',materialCodes:['10','20'],selectedMoldSetId:undefined,evidence:'',reason:''});entityPreview.value=null;entityError.value='';entityDialog.value=true;void searchProducts('')}
async function confirmEntity(){
  if(entitySaving.value||!canWrite.value)return
  if(!entityForm.productLineId||!entityForm.referencePhoneModelId||!entityForm.holeType||!entityForm.targetPhoneModelId||!entityForm.materialCodes.length||!entityForm.evidence.trim()||!entityForm.reason.trim()){entityError.value=uiText('请选择产品、已开模机型、新增适用机型、孔位类型和材质，并填写工程确认依据与原因');return}
  if(sameEntityPhones.value){entityError.value=uiText('已开模机型与新增适用机型不能相同');return}
  if(entityPreviewLoading.value||entityPreviewError.value||!entityPreview.value?.codes.length){entityError.value=uiText('请先完成编码预览后再确认');return}
  if(entityPreview.value.matchStatus==='multiple'&&!entityForm.selectedMoldSetId){entityError.value=uiText('找到多套符合条件的实体模具，请选择实际共用模具');return}
  entitySaving.value=true;entityError.value=''
  try{const result=await confirmAutoMatchedMapping(entityPayload());entityDialog.value=false;const finished=result.finishedProductMappings;if(finished?.status==='created'){ElMessage.success(uiText('共模已确认，并自动生成 {value0} 条共用成品映射',{value0:finished.mappings.length}));ElMessage.info(uiText('共用成品映射码：{value0}',{value0:finished.mappings.map(row=>row.mappingCode).join(' / ')}));if(finished.messages.length)ElMessage.warning(finished.messages.map(message=>uiText(message)).join('；'))}else{ElMessage.success(uiText('工程确认完成，机型已加入共模关系'));ElMessage.info(finished?.messages.map(message=>uiText(message)).join('；')||uiText('普通 SKU 或完整模具关系未齐，成品映射待补'))}await load();await loadEntityMappings();if(result.mappings.length)ElMessage.info(uiText('模具映射码：{value0}',{value0:result.mappings.map(row=>row.mappingCode).join(' / ')}))}
  catch(e){entityError.value=e instanceof Error?e.message:uiText('确认失败，请核对后重试')}
  finally{entitySaving.value=false}
}
async function save(){
  if(saving.value||readonly.value||!canWrite.value)return
  if(!form.reason.trim()||!form.evidence.trim()){saveError.value=uiText('请填写确认依据和修改原因');return}
  if(form.status==='active'&&(!form.productLineId||!form.sourcePhoneModelId||!form.referencePhoneModelId||!form.manufacturingRelationId)){saveError.value=uiText('请选择产品、手机和已有工程关系');return}
  saving.value=true;saveError.value=''
  try{await saveMoldShareMapping(editingId.value,{...form});open.value=false;ElMessage.success(uiText('共模映射已保存'));await load()}
  catch(e){saveError.value=e instanceof Error?e.message:uiText('保存失败，请重试')}
  finally{saving.value=false}
}
onMounted(()=>{void load();void loadEntityMappings();void searchProducts('')})
onBeforeUnmount(()=>{++loadEpoch;++productEpoch;++entityListEpoch})
</script>

<template>
  <section class="mapping-page">
    <h2>{{uiText('手机型号共模映射')}}</h2>
    <p>{{uiText('工程人员在此确认已开模机型与目标机型共用同一实体；确认后映射码生效。不会创建新实物套，也不改变手机及成品编码。')}}</p>
    <div class="filters filters--desktop">
      <el-input v-model="filters.keyword" :placeholder="uiText('搜索模具码、成品映射码或机型')" clearable @keyup.enter="query"/>
      <el-select v-model="filters.holeType" :placeholder="uiText('孔型')" clearable><el-option v-for="hole in (['large','precision','mixed'] as const)" :key="hole" :value="hole" :label="uiText(holeTypeNames[hole])"/></el-select>
      <el-select v-model="filters.status"><el-option value="active" :label="uiText('有效')"/><el-option value="inactive" :label="uiText('已停用')"/><el-option value="needs_review" :label="uiText('待核对')"/><el-option value="all" :label="uiText('全部')"/></el-select>
      <div class="filter-actions"><el-button type="primary" @click="query">{{uiText('查询')}}</el-button><el-button @click="reset">{{uiText('重置')}}</el-button></div>
    </div>
    <div class="filters filters--mobile">
      <el-input v-model="filters.keyword" :placeholder="uiText('搜索模具码、成品映射码或机型')" clearable @keyup.enter="query"/>
      <el-button type="primary" :icon="Search" :aria-label="uiText('查询')" :title="uiText('查询')" @click="query"/>
      <el-button :type="mobileFiltersOpen || activeFilterCount ? 'primary' : 'default'" :icon="Filter" :aria-label="uiText('更多筛选')" :title="uiText('更多筛选')" :aria-expanded="mobileFiltersOpen" @click="mobileFiltersOpen=!mobileFiltersOpen"><span v-if="activeFilterCount" class="filter-count">{{activeFilterCount}}</span></el-button>
      <div v-if="mobileFiltersOpen" class="filters-advanced">
        <el-select v-model="filters.holeType" :placeholder="uiText('孔型')" clearable @change="query"><el-option v-for="hole in (['large','precision','mixed'] as const)" :key="hole" :value="hole" :label="uiText(holeTypeNames[hole])"/></el-select>
        <el-select v-model="filters.status" @change="query"><el-option value="active" :label="uiText('有效')"/><el-option value="inactive" :label="uiText('已停用')"/><el-option value="needs_review" :label="uiText('待核对')"/><el-option value="all" :label="uiText('全部')"/></el-select>
        <el-button text @click="reset">{{uiText('重置筛选')}}</el-button>
      </div>
    </div>
    <div class="tools"><el-button v-if="canWrite" class="add-mapping-button" @click="openEntityConfirmation">{{uiText('新增共模映射')}}</el-button></div>
    <el-alert v-if="error||entityListError" :title="error||entityListError" type="error" :closable="false"><el-button link @click="query">{{uiText('重试')}}</el-button></el-alert>
    <div v-if="!error&&!entityListError" class="mapping-cards" v-loading="loading||entityLoading">
      <article v-for="row in visibleMappings" :key="row.key" class="mapping-card">
        <header><strong>{{row.code}}</strong><el-tag size="small" :type="row.status==='active'?'success':row.status==='needs_review'?'warning':'info'">{{uiText(row.status==='active'?'有效':row.status==='needs_review'?'待核对':'已停用')}}</el-tag></header>
        <dl><div><dt>{{uiText('材质')}}／{{uiText('孔型')}}</dt><dd>{{row.materialCode==='10'?'TPU':row.materialCode==='20'?'PC':row.materialCode||'—'}} · {{uiText(holeTypeNames[row.holeType as keyof typeof holeTypeNames]||row.holeType)}}</dd></div><div><dt>{{uiText('适用机型')}}</dt><dd><span v-for="[id,name] in row.phones" :key="id">{{name}}</span><span v-if="!row.phones.size">—</span></dd></div><div><dt>{{uiText('共用的模具编码')}}</dt><dd><span v-for="code in row.codes" :key="code">{{code}}</span><span v-if="!row.codes.size">—</span></dd></div><div><dt>{{uiText('共用成品映射码')}}</dt><dd><span v-for="code in row.finishedCodes" :key="code">{{code}}</span><span v-if="!row.finishedCodes.size">{{uiText('成品映射待补（需 SKU、最终工序及完整模具关系）')}}</span></dd></div></dl>
      </article>
      <p v-if="!visibleMappings.length&&!loading&&!entityLoading" class="mapping-empty">{{uiText('暂无共模映射，请点击新增共模映射录入')}}</p>
    </div>
    <el-table v-if="!error&&!entityListError" class="mapping-table" v-loading="loading||entityLoading" :data="visibleMappings" row-key="key" :empty-text="uiText('暂无共模映射，请点击新增共模映射录入')">
      <el-table-column prop="code" :label="uiText('模具编码')" min-width="180"/>
      <el-table-column :label="uiText('材质')" width="100"><template #default="{row}">{{row.materialCode==='10'?'TPU':row.materialCode==='20'?'PC':row.materialCode||'—'}}</template></el-table-column>
      <el-table-column :label="uiText('孔型')" width="110"><template #default="{row}">{{uiText(holeTypeNames[row.holeType as keyof typeof holeTypeNames]||row.holeType)}}</template></el-table-column>
      <el-table-column :label="uiText('适用机型')" min-width="250"><template #default="{row}"><div v-for="[id,name] in row.phones" :key="id">{{name}}</div></template></el-table-column>
      <el-table-column :label="uiText('共用的模具编码')" min-width="210"><template #default="{row}"><div v-for="code in row.codes" :key="code">{{code}}</div><span v-if="!row.codes.size">—</span></template></el-table-column>
      <el-table-column :label="uiText('共用成品映射码')" min-width="220"><template #default="{row}"><div v-for="code in row.finishedCodes" :key="code">{{code}}</div><span v-if="!row.finishedCodes.size">{{uiText('成品映射待补（需 SKU、最终工序及完整模具关系）')}}</span></template></el-table-column>
      <el-table-column :label="uiText('状态')" width="110"><template #default="{row}"><el-tag :type="row.status==='active'?'success':row.status==='needs_review'?'warning':'info'">{{uiText(row.status==='active'?'有效':row.status==='needs_review'?'待核对':'已停用')}}</el-tag></template></el-table-column>
    </el-table>
    <el-pagination v-if="!error&&!entityListError" layout="total,prev,pager,next" :total="groupedMappings.length" :current-page="entityPage" :page-size="20" @current-change="entityPage=$event"/>
    <el-dialog v-model="entityDialog" :title="uiText('新增共模映射')" width="min(720px,96vw)" :close-on-click-modal="false" :close-on-press-escape="!entitySaving" :show-close="!entitySaving">
      <el-alert v-if="entityError" :title="entityError" type="error" :closable="false"/>
      <el-form label-position="top" :disabled="entitySaving">
        <el-form-item :label="uiText('产品')" required><el-select :model-value="entityForm.productLineId||undefined" :placeholder="uiText('请选择产品')" filterable remote :remote-method="searchProducts" :loading="productBusy" @update:model-value="entityForm.productLineId=$event"><el-option v-for="p in products" :key="p.productId" :value="p.productId" :label="p.productName"/></el-select></el-form-item>
        <el-form-item :label="uiText('已开模机型')" required><SharedCompatiblePhoneSelector :model-value="entityForm.referencePhoneModelId?[entityForm.referencePhoneModelId]:[]" :multiple="false" @update:model-value="entityForm.referencePhoneModelId=$event[0]||0"/><small>{{uiText('选择已经有实际模具、准备作为共模来源的机型。')}}</small></el-form-item>
        <el-form-item :label="uiText('新增适用机型')" required><SharedCompatiblePhoneSelector :model-value="entityForm.targetPhoneModelId?[entityForm.targetPhoneModelId]:[]" :multiple="false" @update:model-value="entityForm.targetPhoneModelId=$event[0]||0"/></el-form-item>
        <el-form-item :label="uiText('孔位类型')" required><el-select v-model="entityForm.holeType" :placeholder="uiText('请选择孔位类型')"><el-option v-for="hole in (['large','precision','mixed'] as const)" :key="hole" :value="hole" :label="uiText(holeTypeNames[hole])"/></el-select></el-form-item>
        <el-form-item :label="uiText('材质（可多选）')" required><el-checkbox-group v-model="entityForm.materialCodes"><el-checkbox value="10">TPU</el-checkbox><el-checkbox value="20">PC</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item v-if="entityPreview?.matchStatus==='multiple'" :label="uiText('实际共用模具')" required><el-select v-model="entityForm.selectedMoldSetId" :placeholder="uiText('找到多套符合条件的模具，请选择实际使用的一套')"><el-option v-for="row in entityPreview.candidates" :key="`${row.itemId}:${row.moldSetId}`" :value="row.moldSetId" :label="candidateLabel(row)"/></el-select></el-form-item>
        <el-form-item :label="uiText('自动匹配结果')"><div v-loading="entityPreviewLoading" class="code-preview"><el-alert v-if="entityPreviewError" :title="entityPreviewError" type="error" :closable="false"/><el-alert v-else-if="entityPreview?.matchStatus==='none'" :title="uiText('未找到该已开模机型对应的有效实体模具，请先在模具管理中补充资料')" type="warning" :closable="false"/><template v-else-if="entityPreview?.codes.length"><div v-for="row in entityPreview.codes" :key="row.materialCode"><strong>{{row.materialCode==='10'?'TPU':'PC'}}</strong><div>{{uiText('原模具编码')}}：{{row.primaryCode}}</div><div>{{uiText('新增机型映射码')}}：{{row.mappingCode}}</div></div><small>{{uiText('系统已自动匹配实体模具；编码均指向同一实体。')}}</small></template><small v-else>{{uiText('选择产品、两个机型、孔位类型和材质后自动匹配。')}}</small></div></el-form-item>
        <el-form-item :label="uiText('工程确认依据')" required><el-input v-model="entityForm.evidence" type="textarea" maxlength="2000"/></el-form-item>
        <el-form-item :label="uiText('确认原因')" required><el-input v-model="entityForm.reason" type="textarea" maxlength="2000"/></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="entitySaving" @click="entityDialog=false">{{uiText('取消')}}</el-button><el-button type="primary" :loading="entitySaving" :disabled="sameEntityPhones" @click="confirmEntity">{{uiText('确认并生效')}}</el-button></template>
    </el-dialog>
    <el-dialog v-model="open" :title="uiText(readonly?'查看共模映射':editingId?'维护共模映射':'新增映射')" width="min(720px,96vw)" :before-close="close" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false"/>
      <p v-if="source">{{uiText('主档')}} #{{source.itemId}} · {{uiText('实物套')}} #{{source.moldSetId}} · {{uiText('更新时间')}} {{source.updatedAt||'—'}}</p>
      <el-alert v-if="source?.relationCurrent===false" :title="uiText('依据已变化，请重新核对')" type="warning" :closable="false"/>
      <el-form label-position="top" :disabled="saving||readonly">
        <el-form-item :label="uiText('产品')" required><el-select :model-value="form.productLineId||undefined" :placeholder="uiText('产品')" filterable remote :remote-method="searchProducts" :loading="productBusy" :disabled="!!editingId" @update:model-value="form.productLineId=$event;clearRelation()"><el-option v-for="p in products" :key="p.productId" :value="p.productId" :label="p.productName"/></el-select></el-form-item>
        <el-form-item :label="uiText('自身机型')" required><SharedCompatiblePhoneSelector :model-value="form.sourcePhoneModelId?[form.sourcePhoneModelId]:[]" :multiple="false" :disabled="!!editingId" @update:model-value="form.sourcePhoneModelId=$event[0]||0;clearRelation()"/></el-form-item>
        <div class="scope"><el-form-item :label="uiText('孔型')" required><el-select v-model="form.holeType" :disabled="!!editingId" @change="clearRelation"><el-option v-for="hole in (['large','precision','mixed'] as const)" :key="hole" :value="hole" :label="uiText(holeTypeNames[hole])"/></el-select></el-form-item><el-form-item :label="uiText('材质')" required><el-select v-model="form.materialCode" :disabled="!!editingId" @change="clearRelation"><el-option value="10" label="TPU / 10"/><el-option value="20" label="PC / 20"/></el-select></el-form-item></div>
        <el-form-item :label="uiText('结构说明')"><el-input v-model="form.structureDescription" :disabled="!!editingId" maxlength="1000" @input="clearRelation"/></el-form-item>
        <el-form-item :label="uiText('已确认的生产与模具关系')" required><el-button :disabled="!form.productLineId||!form.sourcePhoneModelId" @click="selecting=true">{{uiText('选择已有实物适配依据')}}</el-button><span>#{{form.manufacturingRelationId||'—'}} · V{{form.manufacturingRelationVersion||'—'}} · {{targetCode}}</span><small>{{uiText('先在手机资料确认自身机型对原实物套的完整适配，再维护映射。仅有码、没有实物不能沿用。')}}</small></el-form-item>
        <el-form-item :label="uiText('共模基准机型')" required><SharedCompatiblePhoneSelector :model-value="form.referencePhoneModelId?[form.referencePhoneModelId]:[]" :multiple="false" @update:model-value="form.referencePhoneModelId=$event[0]||0"/></el-form-item>
        <el-form-item :label="uiText('确认依据')" required><el-input v-model="form.evidence" type="textarea" maxlength="2000"/></el-form-item>
        <el-form-item v-if="!readonly" :label="uiText('修改原因')" required><el-input v-model="form.reason" type="textarea" maxlength="2000"/></el-form-item>
        <el-form-item v-if="editingId" :label="uiText('状态')"><el-select v-model="form.status"><el-option value="active" :label="uiText('有效')"/><el-option value="inactive" :label="uiText('已停用')"/></el-select></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="close()">{{uiText(readonly?'关闭':'取消')}}</el-button><el-button v-if="!readonly&&canWrite" type="primary" :loading="saving" @click="save">{{uiText('保存')}}</el-button></template>
    </el-dialog>
    <ManufacturingRelationPicker v-if="selecting" :product-line-id="form.productLineId" :phone-model-id="form.sourcePhoneModelId" :hole-type="form.holeType" @close="selecting=false" @selected="adopt"/>
  </section>
</template>

<style scoped>
.mapping-page>h2{margin-top:0}
.add-mapping-button{--el-button-bg-color:#fff;--el-button-text-color:#000;--el-button-hover-bg-color:#fff;--el-button-hover-text-color:#000;--el-button-active-bg-color:#fff;--el-button-active-text-color:#000}
.code-preview{width:100%;padding:10px 12px;border:1px solid var(--el-border-color);border-radius:4px;overflow-wrap:anywhere}.code-preview>div+div{margin-top:8px}
.mapping-page{padding:16px;min-width:0;container-type:inline-size}.mapping-page>p{color:var(--el-text-color-secondary);line-height:1.6}.filters{display:grid;grid-template-columns:repeat(3,minmax(180px,1fr)) auto;gap:12px;margin:16px 0;align-items:center}.filter-actions{display:flex;gap:10px;white-space:nowrap}.filters--mobile{display:none}.filters-advanced{grid-column:1/-1;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;padding-top:8px;border-top:1px solid var(--el-border-color-lighter)}.filters-advanced>.el-select{width:100%;min-width:0}.filter-count{position:absolute;top:-5px;right:-5px;display:grid;min-width:16px;height:16px;place-items:center;padding:0 3px;border-radius:9px;background:var(--el-color-danger);color:#fff;font-size:10px;line-height:1}.tools,.scope{display:flex;gap:10px;flex-wrap:wrap;margin:12px 0}.scope>*{flex:1;min-width:180px}.el-form .el-select{width:100%}small{display:block;color:var(--el-text-color-secondary)}.el-pagination{margin-top:16px;max-width:100%;overflow:auto}.mapping-cards{display:none}.mapping-empty{margin:0;padding:24px 12px;text-align:center;color:var(--el-text-color-secondary)}.mapping-card{padding:10px 11px;border-bottom:1px solid var(--el-border-color-lighter);background:var(--el-bg-color);overflow-wrap:anywhere}.mapping-card header{display:flex;align-items:flex-start;justify-content:space-between;gap:8px;margin-bottom:7px}.mapping-card header strong{color:var(--el-color-primary);font-size:13px}.mapping-card dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px;margin:0}.mapping-card dl>div{min-width:0}.mapping-card dt{color:var(--el-text-color-secondary);font-size:11px}.mapping-card dd{margin:2px 0 0;line-height:1.5}.mapping-card dd span{display:block}
@media(max-width:1100px){.filters{grid-template-columns:repeat(2,minmax(160px,1fr))}.filter-actions{justify-content:flex-start}}
@media(max-width:600px){.mapping-page{padding:8px}.mapping-page>h2,.mapping-page>p{display:none}.filters--desktop{display:none}.filters--mobile{display:grid;grid-template-columns:minmax(0,1fr) 40px 40px;gap:6px;margin:4px 0 8px}.filters--mobile>.el-input,.filters--mobile>.el-button{min-width:0;margin:0}.filters--mobile>.el-input :deep(.el-input__wrapper){min-height:40px}.filters--mobile>.el-button{position:relative;width:40px;height:40px;min-height:40px;padding:0}.filters-advanced{grid-template-columns:1fr;gap:6px;padding-top:8px}.filters-advanced>.el-button{justify-self:end}.tools{margin:0 0 8px}.add-mapping-button{width:100%;min-height:36px}.mapping-table{display:none}.mapping-cards{display:block;min-height:0;border:1px solid var(--el-border-color-lighter);border-radius:5px;overflow:hidden}.mapping-card dl{grid-template-columns:1fr}.mapping-card dl>div:first-child{grid-column:1}}
.mapping-page{padding-top:4px}
</style>
