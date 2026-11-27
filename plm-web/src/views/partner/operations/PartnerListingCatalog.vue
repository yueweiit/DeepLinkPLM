<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {Filter,Search,Upload} from '@element-plus/icons-vue'
import PageContainer from '@/components/PageContainer/index.vue'
import {listedCatalogPage,operationTarget,crmListingCapabilities,crmListingSync} from '@/api/modules/partnerOperations'
import {partnerError} from '@/utils/partnerError'
import type {ListedCatalogRow,CrmListingCapabilities,CrmListingSync} from '@/types/partnerLifecycle'
import CrmListingPushDialog from './CrmListingPushDialog.vue'
import PartnerPhoto from '../PartnerPhoto.vue'
import PhoneModelSummary from '../PhoneModelSummary.vue'
import {catalogPhoneSummary} from '../catalog/catalogPresentation'
const route=useRoute(),router=useRouter(),status=ref<'listed'|'pending'|'unlisted'|'all'>('listed'),keyword=ref(''),page=ref(1),total=ref(0),rows=ref<ListedCatalogRow[]>([]),loading=ref(false),error=ref('')
const labels={listed:'已上架',pending:'待上架',unlisted:'已下架',all:'全部'} as const
const selected=ref<number[]>([]),pushRows=ref<ListedCatalogRow[]>([]),readonlySync=ref(false),capabilities=ref<CrmListingCapabilities>(),syncs=ref<Record<number,CrmListingSync>>({}),syncError=ref(''),mobileFiltersOpen=ref(false)
const selectableRows=computed(()=>rows.value.filter(row=>!!row.listingId&&!row.legacyProduct))
const allPageSelected=computed(()=>selectableRows.value.length>0&&selectableRows.value.every(row=>selected.value.includes(row.listingId!)))
function selectPage(value:boolean){selected.value=value?selectableRows.value.slice(0,20).map(row=>row.listingId!):[]}
const syncLabels={not_submitted:'未提交 CRM',pending:'等待推送',processing:'推送中',partial:'部分失败',synced:'已同步 CRM',failed:'推送失败'} as const
async function loadCrm(serial:number){
 syncError.value=''
 try{const cap=await crmListingCapabilities();if(serial!==generation)return;capabilities.value=cap
  void Promise.all(rows.value.filter(row=>row.listingId&&!row.legacyProduct).map(async row=>[row.listingId!,await crmListingSync(row.listingId!)] as const)).then(result=>{if(serial===generation)syncs.value=Object.fromEntries(result)}).catch(e=>{if(serial===generation)syncError.value=partnerError(e)})
 }catch(e){if(serial===generation)syncError.value=partnerError(e)}
}
function push(){readonlySync.value=false;pushRows.value=rows.value.filter(row=>row.listingId&&selected.value.includes(row.listingId))}
function showSync(row:ListedCatalogRow){readonlySync.value=true;pushRows.value=[row]}
function selectRow(row:ListedCatalogRow,value:string|number|boolean){if(row.listingId)selected.value=value?[...selected.value,row.listingId]:selected.value.filter(id=>id!==row.listingId)}
let generation=0
async function load(){const serial=++generation;loading.value=true;error.value='';selected.value=[];syncs.value={};capabilities.value=undefined;try{const data=await listedCatalogPage({status:status.value,keyword:keyword.value.trim(),page:page.value,size:30});if(serial!==generation)return;rows.value=data.content;total.value=data.totalElements;await loadCrm(serial)}catch(e){if(serial===generation)error.value=partnerError(e)}finally{if(serial===generation)loading.value=false}}
watch(()=>route.fullPath,()=>{const query=String(route.query.status||'listed');status.value=query in labels?query as typeof status.value:'listed';keyword.value=String(route.query.keyword||'');page.value=Math.max(1,Number(route.query.page)||1);void load()},{immediate:true})
onBeforeUnmount(()=>{generation++})
function search(){void router.replace({path:'/partner-listings',query:{status:status.value,keyword:keyword.value.trim()||undefined,page:1}})}
function open(row:ListedCatalogRow){if(row.legacyProduct&&row.productId)void router.push({path:'/partner-products',query:{id:row.productId,from:route.fullPath}});else if(row.listingId)void router.push(operationTarget('listing',row.listingId,route.fullPath));else if(row.productId)void router.push({path:'/partner-products',query:{id:row.productId,from:route.fullPath}})}
const state=(row:ListedCatalogRow)=>labels[row.status as keyof typeof labels]||'待上架'
const phoneCount=(row:ListedCatalogRow)=>new Set((row.phones||[]).map(phone=>'phoneModelId' in phone&&typeof phone.phoneModelId==='number'&&phone.phoneModelId>0?'id:'+phone.phoneModelId:phone)).size
</script>
<template>
 <PageContainer :title='uiText("上架产品目录")' class="listed-catalog">
  <template #actions>
   <el-button @click="router.push('/partner-selections')">{{uiText('选品目录')}}</el-button>
   <el-tooltip v-if="status==='pending'" :content="capabilities?.reason||'批量推送 CRM'" placement="bottom"><span><el-button :icon="Upload" type="primary" :disabled="loading||!selected.length||!capabilities?.authorized" @click="push">{{uiText('推送 CRM')}}</el-button></span></el-tooltip>
   <el-button :loading="loading" @click="load">{{uiText('刷新')}}</el-button>
  </template>
  <div class="listed-filter listed-filter--desktop"><el-radio-group v-model="status" @change="search"><el-radio-button v-for="(label,value) in labels" :key="value" :value="value">{{label}}</el-radio-button></el-radio-group><el-input v-model="keyword" clearable :placeholder="uiText('搜索商品、款式号或手机型号')" @keyup.enter="search"/><el-button @click="search">{{uiText('查询')}}</el-button></div>
  <div class="listed-filter listed-filter--mobile"><el-input v-model="keyword" clearable :placeholder="uiText('搜索商品、款式号或手机型号')" @keyup.enter="search"/><el-button type="primary" :icon="Search" :aria-label="uiText('查询')" :title="uiText('查询')" @click="search"/><el-button :type="mobileFiltersOpen || status !== 'listed' ? 'primary' : 'default'" :icon="Filter" :aria-label="uiText('更多筛选')" :title="uiText('更多筛选')" :aria-expanded="mobileFiltersOpen" @click="mobileFiltersOpen=!mobileFiltersOpen"/><div v-if="mobileFiltersOpen" class="listed-filter-advanced"><el-select v-model="status" :aria-label="uiText('上架状态')" @change="search"><el-option v-for="(label,value) in labels" :key="value" :label="label" :value="value"/></el-select></div></div>
  <div v-if="status==='pending'" class="listing-batch-bar"><el-checkbox :model-value="allPageSelected" :disabled="loading||!capabilities?.authorized" @change="selectPage(Boolean($event))">选择本页可上架产品</el-checkbox><span>已选择 {{selected.length}} 款，最多 20 款</span><el-button link :disabled="!selected.length" @click="selected=[]">清空选择</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load">{{uiText('重试')}}</el-button></el-alert>
  <el-alert v-if="syncError" :title="'CRM 同步状态加载失败：'+syncError" type="warning" :closable="false"/>
  <div class="listed-scroll" v-loading="loading">
   <div class="listed-head"><span>{{uiText('图片')}}</span><span>{{uiText('款式号／商品名称')}}</span><span>{{uiText('经营手机型号')}}</span><span>{{uiText('颜色')}}</span><span>{{uiText('上架状态')}}</span><span>{{uiText('渠道')}}</span><span>{{uiText('操作')}}</span></div>
   <article v-for="row in rows" :key="(row.listingId?'listing:':'product:')+(row.listingId||row.productId||row.id)" class="listed-row">
    <div class="listed-photo"><PartnerPhoto v-if="row.photoId" :id="row.photoId" :allow-retry="true"/><span v-else>{{uiText('暂无图片')}}</span></div>
    <div class="listed-name"><small>{{row.catalogStyleLabel||row.catalogStyleCode||row.code}}</small><strong>{{row.name}}</strong><el-checkbox v-if="status==='pending'" :model-value="!!row.listingId&&selected.includes(row.listingId)" :disabled="!row.listingId||row.legacyProduct||!capabilities?.authorized||loading||(!selected.includes(row.listingId)&&selected.length>=20)" :title="row.legacyProduct?'历史记录缺少明确上架依据':capabilities?.reason" @update:model-value="selectRow(row,$event)">选择上架</el-checkbox></div>
    <div class="listed-models"><span class="field-label">{{uiText('手机型号')}}</span><PhoneModelSummary :phones="row.phones||[]" :count="phoneCount(row)" :fallback="row.phones?.length?catalogPhoneSummary(row):uiText('见经营规格')" :original-text="row.modelText" scope="listing"/></div>
    <div class="listed-color"><span class="field-label">{{uiText('颜色')}}</span>{{row.color||uiText('见经营规格')}}</div>
    <div class="listed-status"><el-tag :type="row.status==='listed'?'success':row.status==='unlisted'?'info':'warning'">{{state(row)}}</el-tag><small v-if="row.listingId&&syncs[row.listingId]">{{syncLabels[syncs[row.listingId].status]}}</small><small v-if="row.integrationStatus==='not_connected'&&!capabilities?.canSubmit">{{uiText('未接通')}}</small></div>
    <div class="listed-channel"><span class="field-label">{{uiText('渠道')}}</span>{{row.channel||uiText('未记录')}}</div>
    <div class="listed-actions"><el-button link type="primary" @click="open(row)">{{uiText('查看依据')}}</el-button><el-button v-if="row.listingId&&!row.legacyProduct" link type="primary" @click="showSync(row)">CRM 同步</el-button></div>
   </article>
   <el-empty v-if="!loading&&!rows.length" :description="status==='listed'?uiText('暂无已上架商品；未接通的准备记录可切到待上架查看'):uiText('当前范围暂无记录')"/>
  </div>
  <footer><span>{{uiText('共')}}{{total}}{{uiText('条')}}</span><el-pagination :current-page="page" :page-size="30" :total="total" :pager-count="5" layout="prev,pager,next" @current-change="router.replace({path:'/partner-listings',query:{...route.query,page:$event}})"/></footer>
  <CrmListingPushDialog v-if="pushRows.length" :rows="pushRows" :readonly="readonlySync" @close="pushRows=[]" @saved="load"/>
 </PageContainer>
</template>
<style scoped>
.listed-catalog{height:100%;min-height:0;min-width:0;display:flex;flex-direction:column;gap:6px;container-type:inline-size}.listed-catalog :deep(>.page-panel){padding:6px 10px;flex:none}.listed-catalog :deep(.page-panel-header){margin:0;align-items:center;flex-direction:row;flex-wrap:wrap}.listed-catalog :deep(.page-panel-title){font-size:17px}.listed-filter{display:flex;align-items:center;gap:7px;flex-wrap:wrap;flex:none}.listed-filter>.el-input{flex:1;min-width:180px}.listed-filter--mobile{display:none}.listed-filter-advanced{grid-column:1/-1;display:grid;grid-template-columns:minmax(0,1fr);gap:6px;padding-top:6px;border-top:1px solid var(--el-border-color-lighter)}.listed-filter-advanced>.el-select{width:100%;min-width:0}.listed-scroll{flex:1;min-height:0;min-width:0;overflow-y:auto;border:1px solid var(--el-border-color-lighter);border-radius:5px;background:white}.listed-head,.listed-row{display:grid;grid-template-columns:60px minmax(0,1.2fr) minmax(0,1.6fr) minmax(0,1fr) 100px minmax(0,.8fr) 88px}.listed-head{position:sticky;top:0;background:var(--el-fill-color-light);font-size:12px;color:var(--el-text-color-secondary)}.listed-head>span,.listed-row>div{padding:4px 8px;min-width:0;overflow-wrap:anywhere}.listed-row{min-height:52px;border-top:1px solid var(--el-border-color-lighter);font-size:13px;line-height:1.5;align-items:center}.listed-name,.listed-models{display:flex;flex-direction:column}.listed-name small,.listed-row small{display:block;color:var(--el-text-color-secondary);font-size:12px;line-height:1.5}.listed-models strong{font-size:15px;color:#284d7c}.listed-photo :deep(.partner-photo){width:44px;height:44px}.listed-photo>span{font-size:12px;color:var(--el-text-color-secondary)}.field-label{display:none}.listed-actions :deep(.el-button){white-space:normal;height:auto;padding:0;max-width:100%;line-height:1.5}.listed-catalog footer{display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap;font-size:12px;flex:none}
@container(max-width:700px){.listed-filter--desktop{display:none}.listed-filter--mobile{display:grid;grid-template-columns:minmax(0,1fr) 40px 40px;align-items:center;gap:6px;flex-wrap:nowrap}.listed-filter--mobile>.el-input,.listed-filter--mobile>.el-button{min-width:0;margin:0}.listed-filter--mobile>.el-input :deep(.el-input__wrapper){min-height:40px}.listed-filter--mobile>.el-button{width:40px;height:40px;min-height:40px;padding:0}}
@container(max-width:900px){.listed-head{display:none}.listed-row{display:grid;grid-template-columns:44px minmax(0,1.1fr) minmax(0,.8fr) minmax(0,1.1fr) minmax(0,.7fr) 72px;grid-template-rows:auto auto;padding:5px 6px;gap:1px 7px}.listed-row>div{padding:0}.listed-photo{grid-column:1;grid-row:1/3;align-self:start;margin-top:3px}.listed-name{grid-column:2/4;grid-row:1}.listed-models{grid-column:4/6;grid-row:1}.listed-models>.field-label{display:none}.listed-color{grid-column:2/4;grid-row:2}.listed-status{grid-column:4;grid-row:2}.listed-channel{grid-column:5;grid-row:2}.listed-actions{grid-column:6;grid-row:1/3}.field-label{display:inline;color:var(--el-text-color-secondary);margin-right:5px;font-size:12px}}
@container(max-width:480px){.listed-row{grid-template-columns:44px minmax(0,1fr) minmax(0,1fr) 64px}.listed-name{grid-column:2/4;grid-row:1}.listed-models{grid-column:2/4;grid-row:2}.listed-color{grid-column:2;grid-row:3}.listed-status{grid-column:3;grid-row:3}.listed-channel{grid-column:2/4;grid-row:4}.listed-actions{grid-column:4;grid-row:1/4}}
</style>
<style scoped>
.listed-actions{display:flex;flex-direction:column;align-items:flex-start;gap:4px}.listed-actions :deep(.el-button+.el-button){margin-left:0}.listed-name :deep(.el-checkbox){height:24px;font-size:12px}
.listing-batch-bar{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:5px 8px;background:var(--el-color-primary-light-9);border:1px solid var(--el-color-primary-light-7);border-radius:5px;font-size:13px}
</style>
